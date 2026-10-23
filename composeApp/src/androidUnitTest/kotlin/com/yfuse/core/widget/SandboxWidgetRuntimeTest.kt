package com.yfuse.core.widget

import com.yfuse.feature.widgets.demoWidgetSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxWidgetRuntimeTest {
    @Test
    fun shipped_demo_installs_and_filters_real_javascript_results() =
        runBlocking {
            val engine = runtime()
            val widget = installed(engine, demoWidgetSource)
            assertEquals(
                demoWidgetSource,
                java.io
                    .File("../examples/widgets/yfuse-demo.js")
                    .readText()
                    .trim(),
            )
            assertEquals(
                6,
                parseWidgetMedia(engine.invoke(widget, "getMovies", buildJsonObject { put("kind", "all") }, 3)).size,
            )
            val movies =
                parseWidgetMedia(engine.invoke(widget, "getMovies", buildJsonObject { put("kind", "movie") }, 3))
            assertEquals(3, movies.size)
            assertTrue(movies.all { it.mediaType == "movie" })
        }

    @Test
    fun rejected_promises_are_reported_as_component_errors(): Unit =
        runBlocking {
            val engine = runtime()
            val widget = installed(engine, "$source\nasync function fail(){throw Error('source unavailable');}")
            val error = assertFails { engine.invoke(widget, "fail", JsonNull, 3) }
            assertTrue(error.message.orEmpty().contains("source unavailable"), error.message)
        }

    private val source =
        """
        var WidgetMetadata = {id:'test',title:'Test',modules:[{title:'List',functionName:'list'}]};
        async function list(params){return [{id:String(params.page),title:'Page '+params.page}];}
        """.trimIndent()

    private fun runtime() =
        SandboxWidgetRuntime({ _, _ -> error("No network expected") }, { _, _ -> null }, { _, _, _ -> })

    private suspend fun installed(
        runtime: WidgetRuntime,
        code: String = source,
    ) = InstalledWidget(parseWidgetMetadata(runtime.metadata(code), "test"), code, "test", "digest")

    @Test fun executes_real_javascript_async_functions_and_serializes_media() =
        runBlocking {
            val engine = runtime()
            val widget = installed(engine)
            val items = parseWidgetMedia(engine.invoke(widget, "list", buildJsonObject { put("page", 3) }, 3))
            assertEquals("Page 3", items.single().title)
        }

    @Test fun metadata_cannot_fetch_network_or_write_storage(): Unit =
        runBlocking {
            val engine = runtime()
            assertFails { engine.metadata("Widget.storage.set('x','y'); $source") }
            assertFails { engine.metadata("Widget.http.get('https://example.com'); $source") }
        }

    @Test fun busy_script_is_interrupted_and_next_script_still_works() =
        runBlocking {
            val engine = runtime()
            val widget = installed(engine, "$source\nfunction spin(){while(true){}}")
            withTimeout(5000) { assertFails { engine.invoke(widget, "spin", JsonNull, 1) } }
            assertEquals(
                "Page 1",
                parseWidgetMedia(
                    engine.invoke(
                        widget,
                        "list",
                        buildJsonObject {
                            put("page", 1)
                        },
                        3,
                    ),
                ).single().title,
            )
        }

    @Test fun cancellation_interrupts_pending_host_calls() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val engine =
                SandboxWidgetRuntime({ _, _ ->
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }, { _, _ -> null }, { _, _, _ -> })
            val widget =
                installed(
                    engine,
                    "$source\nasync function network(){return await Widget.http.get('https://example.com');}",
                )
            val job = launch { engine.invoke(widget, "network", JsonNull, 30) }
            withTimeout(5000) {
                entered.await()
                job.cancelAndJoin()
                cancelled.await()
            }
            assertTrue(job.isCancelled)
        }

    @Test fun dom_bridge_and_storage_use_only_the_current_widget_namespace() =
        runBlocking {
            val values = mutableMapOf<String, String?>()
            val engine =
                SandboxWidgetRuntime({ _, _ -> "" }, { id, key -> values["$id:$key"] }, { id, key, value ->
                    values["$id:$key"] =
                        value
                })
            val widget =
                installed(
                    engine,
                    """
                    $source
                    function parse(){
                      Widget.storage.set('count',2);
                      var d=Widget.dom.parse('<a href="/one">One</a>');
                      var n=Widget.dom.select(d,'a')[0];
                      return [{id:'x',title:Widget.dom.text(n),link:Widget.dom.attr(n,'href')}];
                    }
                    """.trimIndent(),
                )
            assertEquals("One", parseWidgetMedia(engine.invoke(widget, "parse", JsonNull, 3)).single().title)
            assertEquals("2", values["test:count"])
            assertEquals(setOf("test:count"), values.keys)
        }

    @Test fun repeated_dom_parsing_is_bounded_even_when_parent_handles_are_removed() =
        runBlocking {
            val engine = runtime()
            val widget =
                installed(
                    engine,
                    """
                    $source
                    function exhaust(){
                      var html='<p>'+'x'.repeat(1024*1024)+'</p>', children=[];
                      for(var i=0;i<100;i++){
                        var document=Widget.dom.parse(html);
                        children.push(Widget.dom.select(document,'p')[0]);
                        Widget.dom.remove(document);
                      }
                      return children;
                    }
                    """.trimIndent(),
                )
            val failure = assertFails { engine.invoke(widget, "exhaust", JsonNull, 3) }
            assertTrue(failure.message.orEmpty().contains("HTML 解析资源已达到上限"), failure.message)
            val normal = engine.invoke(widget, "list", buildJsonObject { put("page", 2) }, 3)
            assertEquals("Page 2", parseWidgetMedia(normal).single().title)
        }

    @Test fun metadata_dom_parsing_has_the_same_host_budget() =
        runBlocking {
            val engine = runtime()
            val failure =
                assertFails {
                    engine.metadata(
                        "var html='x'.repeat(1024*1024); for(var i=0;i<100;i++) Widget.dom.parse(html); $source",
                    )
                }
            assertTrue(failure.message.orEmpty().contains("HTML 解析资源已达到上限"), failure.message)
            assertEquals("test", parseWidgetMetadata(engine.metadata(source), "fallback").id)
        }

    @Test fun dom_node_budget_counts_the_trees_instead_of_only_exported_handles() =
        runBlocking {
            val engine = runtime()
            val widget =
                installed(
                    engine,
                    "$source\nfunction dense(){for(var i=0;i<100;i++) Widget.dom.parse('<i></i>'.repeat(8000));}",
                )
            val failure = assertFails { engine.invoke(widget, "dense", JsonNull, 3) }
            assertTrue(failure.message.orEmpty().contains("HTML 节点数量超限"), failure.message)
        }

    @Test fun dom_document_limit_counts_utf8_bytes_and_selection_stops_at_its_result_limit() =
        runBlocking {
            val engine = runtime()
            val widget =
                installed(
                    engine,
                    """
                    $source
                    function oversized(){return Widget.dom.parse('界'.repeat(1024*1024));}
                    function select(){return Widget.dom.select(Widget.dom.parse('<a>x</a>'.repeat(2500)),'a').length;}
                    """.trimIndent(),
                )
            val failure = assertFails { engine.invoke(widget, "oversized", JsonNull, 3) }
            assertTrue(failure.message.orEmpty().contains("单个 HTML 文档过大"), failure.message)
            assertEquals("2000", engine.invoke(widget, "select", JsonNull, 3))
        }

    @Test fun host_network_failures_never_enter_script_or_ui_as_jvm_stacks() =
        runBlocking {
            val engine =
                SandboxWidgetRuntime(
                    { _, _ ->
                        throw SocketException("Connection reset https://private.invalid/?token=private-fixture")
                    },
                    { _, _ -> null },
                    { _, _, _ -> },
                )
            val widget =
                installed(
                    engine,
                    """
                    $source
                    async function uncaught(){ return await Widget.http.get('https://example.invalid'); }
                    async function caught(){
                      try { await Widget.http.get('https://example.invalid'); }
                      catch(error) { return [{id:'error',title:String(error)}]; }
                    }
                    """.trimIndent(),
                )
            val failure = assertFails { engine.invoke(widget, "uncaught", JsonNull, 3) }.message.orEmpty()
            val caught = parseWidgetMedia(engine.invoke(widget, "caught", JsonNull, 3)).single().title
            for (message in listOf(failure, caught)) {
                assertTrue(message.contains("连接暂时中断"))
                assertTrue(message.length < 80)
                assertFalse(message.contains("java."))
                assertFalse(message.contains("private-fixture"))
                assertFalse(message.contains("https://"))
                assertFalse(message.contains("\n"))
            }
        }

    @Test fun script_error_urls_headers_and_long_messages_are_not_returned_to_the_ui() =
        runBlocking {
            val engine = runtime()
            for (message in listOf(
                "https://private.invalid/?token=fixture",
                "Authorization: Bearer private-fixture",
                "x".repeat(1000),
            )) {
                val widget = installed(engine, "$source\nasync function fail(){throw Error('$message');}")
                val error = assertFails { engine.invoke(widget, "fail", JsonNull, 3) }
                assertEquals("组件内容暂时不可用，请稍后重试", error.message)
            }
        }
}
