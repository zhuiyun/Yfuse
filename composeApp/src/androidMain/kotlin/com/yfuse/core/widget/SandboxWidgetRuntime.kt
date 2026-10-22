package com.yfuse.core.widget

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicInteger

/** Fresh engines have no Java reflection, filesystem, process or application object bindings. */
class SandboxWidgetRuntime(
    private val request: suspend (JsonObject, Boolean) -> String,
    private val readStorage: (String, String) -> String?,
    private val writeStorage: (String, String, String?) -> Unit,
) : WidgetRuntime {
    private val engines = Semaphore(3)

    override suspend fun metadata(source: String): String =
        execute(source, null, 2) {
            "JSON.stringify(typeof WidgetMetadata === 'undefined' ? null : WidgetMetadata)"
        }

    override suspend fun invoke(
        widget: InstalledWidget,
        function: String,
        argument: JsonElement,
        timeoutSeconds: Int,
    ): String {
        require(function.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "无效函数名" }
        return execute(widget.source, widget.metadata.id, timeoutSeconds) {
            """
            (async function(){
              if(typeof $function !== 'function') throw Error('组件未提供此函数');
              return JSON.stringify(await $function($argument));
            })()
            """.trimIndent()
        }
    }

    private suspend fun execute(
        source: String,
        widgetId: String?,
        seconds: Int,
        expression: () -> String,
    ): String =
        engines.withPermit {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(seconds.coerceIn(1, 60) * 1000L) {
                    val js = QuickJs.create(Dispatchers.IO)
                    try {
                        js.memoryLimit = 32 * 1024 * 1024L
                        js.maxStackSize = 512 * 1024L
                        js.evaluationTimeoutMillis = seconds.coerceIn(1, 60) * 1000L
                        val requests = AtomicInteger()
                        val networkSlots = Semaphore(4)
                        js.asyncFunction("__yfRequest") { args ->
                            check(widgetId != null) { "安装预览不允许网络请求" }
                            check(requests.incrementAndGet() <= 40) { "单次组件请求数量超限" }
                            val options = widgetJson.parseToJsonElement(args[0] as String).jsonObject
                            networkSlots.withPermit { request(options, args.getOrNull(1) == true) }
                        }
                        js.function("__yfRead") { args -> widgetId?.let { readStorage(it, args[0] as String) } }
                        js.function("__yfWrite") { args ->
                            check(widgetId != null) { "安装预览不允许写入数据" }
                            val key = args[0] as String
                            val value = args.getOrNull(1) as? String
                            require(key.length <= 120 && (value?.length ?: 0) <= 65536) { "组件存储内容过大" }
                            writeStorage(widgetId, key, value)
                        }
                        bindDom(js)
                        js.evaluate<Any?>(WIDGET_BRIDGE)
                        js.evaluate<Any?>(source, filename = "widget.js")
                        // evaluate waits for jobs but does not unwrap the Promise's return value.
                        js.evaluate<Any?>(
                            """
                            var __yfResult, __yfError = null;
                            Promise.resolve(${expression()}).then(
                              function(value){ __yfResult = value; },
                              function(error){
                                __yfError = String(error) + '\n' + String(error && error.stack || '');
                              }
                            );
                            """.trimIndent(),
                            filename = "widget-call.js",
                        )
                        val value =
                            js.evaluate<String?>(
                                "if(__yfError !== null) throw Error(__yfError); __yfResult;",
                                filename = "widget-result.js",
                            )
                        require(value != null && value.length <= 4 * MAX_WIDGET_SOURCE_BYTES) { "组件返回内容为空或过大" }
                        value
                    } finally {
                        js.close()
                    }
                } ?: error("组件执行超时，请重试或调整栏目参数")
            }
        }

    private fun bindDom(js: QuickJs) {
        val nodes = mutableMapOf<String, Element>()
        var next = 0

        fun retain(element: Element): String {
            check(nodes.size < 10000) { "HTML 节点数量超限" }
            return "node${next++}".also { nodes[it] = element }
        }
        js.function("__yfDom") { args ->
            val op = args[0] as String
            val target = args.getOrNull(1) as? String ?: ""
            val selector = args.getOrNull(2) as? String ?: ""
            when (op) {
                "parse" -> {
                    require(target.length <= 2 * MAX_WIDGET_SOURCE_BYTES)
                    retain(Jsoup.parse(target))
                }
                "select" ->
                    JsonArray(
                        (nodes[target]?.select(selector) ?: emptyList<Element>()).take(2000).map {
                            JsonPrimitive(retain(it))
                        },
                    ).toString()
                "text" -> nodes[target]?.text().orEmpty()
                "html" -> nodes[target]?.html().orEmpty()
                "attr" -> nodes[target]?.attr(selector).orEmpty()
                "remove" -> {
                    nodes.remove(target)
                    ""
                }
                else -> error("不支持的 HTML 操作")
            }
        }
    }
}

internal val WIDGET_BRIDGE =
    """
    var console = {log:function(){}, info:function(){}, warn:function(){}, error:function(){}, debug:function(){}};
    var Widget = {
      http: {
        request: async function(o){return JSON.parse(await __yfRequest(JSON.stringify(o),false));},
        get: async function(url,o){return Widget.http.request(Object.assign({},o||{},{url:url,method:'GET'}));},
        post: async function(url,body,o){return Widget.http.request(Object.assign({},o||{},{url:url,method:'POST',data:body}));}
      },
      tmdb: {get:async function(path,o){var r=JSON.parse(await __yfRequest(JSON.stringify(Object.assign({},o||{},{url:path,method:'GET'})),true));if(!r.ok)throw Error('TMDB HTTP '+r.status);return r.data;}},
      storage: {
        get:function(k,d){var v=__yfRead(String(k));return v==null?d:JSON.parse(v);},
        getAsync:async function(k,d){return Widget.storage.get(k,d);},
        set:function(k,v){__yfWrite(String(k),JSON.stringify(v));},
        remove:function(k){__yfWrite(String(k),null);}
      },
      dom:{
        parse:function(h){return __yfDom('parse',String(h));},
        select:function(n,s){return JSON.parse(__yfDom('select',n,s));},
        text:function(n){return __yfDom('text',n);},
        html:function(n){return __yfDom('html',n);},
        attr:function(n,a){return __yfDom('attr',n,a);},
        remove:function(n){return __yfDom('remove',n);}
      }
    };
    """.trimIndent()
