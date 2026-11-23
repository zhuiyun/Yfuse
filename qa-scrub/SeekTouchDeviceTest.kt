package com.yfuse.feature.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekTouchDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val ui = inst.uiAutomation
    private val folder get() = File(ctx.filesDir, "touch-results").apply { mkdirs() }
    private fun shot() = ui.takeScreenshot().let { original ->
        if (original.config == Bitmap.Config.HARDWARE) original.copy(Bitmap.Config.ARGB_8888,false).also { original.recycle() } else original
    }
    private fun save(bitmap: Bitmap, name: String) {
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 85, it) }
    }
    private fun clock(b: Bitmap): Double? {
        val scale = min(b.width / 320.0, b.height / 180.0)
        val x0 = (b.width - 320*scale)/2
        val y0 = (b.height - 180*scale)/2
        for (y in listOf(86, 92, 77, 99)) {
            val values=(0..17).map { i ->
                val c=b.getPixel((x0+(58+12*i)*scale).toInt(), (y0+y*scale).toInt())
                (Color.red(c)+Color.green(c)+Color.blue(c))/3
            }
            val dark=listOf(values[1],values[15],values[17]).sorted()[1]
            val light=listOf(values[0],values[2],values[16]).sorted()[1]
            if (light-dark<45) continue
            val bits=values.map { if(it>(light+dark)/2) 1 else 0 }
            if(bits.take(3)!=listOf(1,0,1) || bits.takeLast(3)!=listOf(0,1,0) || bits.subList(3,14).sum()%2!=bits[14]) continue
            return bits.subList(3,14).fold(0) { a,v -> 2*a+v } / 8.0
        }
        return null
    }
    private fun rail(n: AccessibilityNodeInfo?): Rect? {
        if(n==null) return null
        val r=Rect().also(n::getBoundsInScreen)
        if(r.width()>200 && r.width()>r.height()*3 && (n.rangeInfo!=null || n.className?.toString()?.endsWith("SeekBar")==true || n.stateDescription?.toString()?.contains("播放进度")==true)) return r
        for(i in 0 until n.childCount) rail(n.getChild(i))?.let { return it }
        return null
    }
    private fun finger(x0: Int, x1: Int, y: Int) {
        val down=SystemClock.uptimeMillis()
        fun send(action: Int, x: Int) {
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x.toFloat(),y.toFloat(),0)
            e.source=InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.injectInputEvent(e,true)) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN,x0)
        if (x0!=x1) for (i in 1..12) {
            SystemClock.sleep(17)
            send(MotionEvent.ACTION_MOVE,x0+(x1-x0)*i/12)
        }
        send(MotionEvent.ACTION_UP,x1)
    }
    private fun reveal(): Rect? {
        rail(ui.rootInActiveWindow)?.let { return it }
        repeat(4) {
            val b=shot()
            val x=(b.width*0.52).toInt()
            finger(x,x,(b.height*0.33).toInt());b.recycle()
            repeat(12) {
                SystemClock.sleep(75)
                rail(ui.rootInActiveWindow)?.let { return it }
            }
        }
        return null
    }
    @Test fun dragPlayerTimeRail() {
        val input=JSONObject(File(ctx.filesDir,"touch-input.json").readText())
        val output=File(folder,"attempts.jsonl")
        val failures=mutableListOf<String>()
        fun note(record:JSONObject) {output.appendText(record.toString()+"\n");println(record.toString())}
        for (name in input.keys().asSequence().sorted()) {
            val entry=input.getJSONObject(name)
            val duration=entry.getDouble("seconds")
            val fractions=entry.getJSONArray("targets")
            val item=externalPlaybackItem(entry.getString("url"),name)
            val intent=PlayerActivity.intent(ctx,listOf(item),0,0L,PlayerEngine.YCore,DecoderMode.Hardware,false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<PlayerActivity>(intent).use {
                val until=SystemClock.uptimeMillis()+22000
                var first:Double?=null
                while(first==null && SystemClock.uptimeMillis()<until) {
                    val b=shot();first=clock(b);b.recycle()
                    if(first==null)SystemClock.sleep(160)
                }
                val start=shot();save(start,"$name-start");start.recycle()
                note(JSONObject().put("case",name).put("initialFrame",first?:-1))
                for (i in 0 until fractions.length()) {
                    val f=fractions.getDouble(i);val target=f*duration
                    val r=reveal()
                    if (r==null) {failures.add("$name missing rail");note(JSONObject().put("case",name).put("error","rail unavailable"));break}
                    fun x(v:Double)=r.left+((r.width()-1)*v).roundToInt()
                    val from=if(f>0.5) 0.15 else 0.83
                    finger(x(from),x(f),r.centerY())
                    val t=SystemClock.elapsedRealtime()
                    var firstFrame:Double?=null;var last:Double?=null;var firstDelay=-1L;var ok=false
                    while(SystemClock.elapsedRealtime()-t<12000) {
                        val b=shot();val s=clock(b);val elapsed=SystemClock.elapsedRealtime()-t
                        if(s!=null && s>=target-3 && s<=target+elapsed/1000.0+3) {
                            if(firstFrame==null) {firstFrame=s;firstDelay=elapsed;save(b,"$name-$i-land")}
                            else if(s-firstFrame>=0.375 && s-firstFrame<=(elapsed-firstDelay)/1000.0+2.5) {last=s;ok=true;save(b,"$name-$i-next")}
                        }
                        b.recycle()
                        if(ok)break
                        SystemClock.sleep(120)
                    }
                    note(JSONObject().put("case",name).put("target",target).put("firstFrame",firstFrame?:-1).put("laterFrame",last?:-1).put("firstSeenMs",firstDelay).put("ok",ok))
                    if(!ok) {failures.add("$name/$i");val b=shot();save(b,"$name-$i-failure");b.recycle()}
                }
            }
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }
}
