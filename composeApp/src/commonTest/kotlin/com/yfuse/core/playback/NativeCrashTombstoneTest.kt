package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeCrashTombstoneTest {
    @Test
    fun only_the_crashing_thread_decides_the_owner() {
        // A vendor codec thread crashes while an idle YCore demux thread sits in the tombstone.
        val tombstone =
            protoTombstone(
                crashingTid = 200,
                threads =
                    listOf(
                        thread(
                            100,
                            "YCore-Demux",
                            frame("/data/app/~~a/com.yfuse/lib/arm64/libycore_demux.so", 0x1234),
                        ),
                        thread(200, "CCodecBufferCha", frame("/system/lib64/libstagefright_ccodec.so", 0x99)),
                    ),
            )

        val report = assertNotNull(parseNativeCrashTombstone(tombstone))

        assertEquals("CCodecBufferCha", report.threadName)
        assertEquals("libstagefright_ccodec.so", report.frames.single().library)
        assertEquals(NativePlaybackComponent.Unknown to NativeCrashAttribution.Unknown, report.attributedComponent())
    }

    @Test
    fun the_first_owner_frame_above_shared_ffmpeg_owns_the_crash() {
        val tombstone =
            protoTombstone(
                crashingTid = 300,
                signalName = "SIGSEGV",
                codeName = "SEGV_MAPERR",
                faultAddress = 0x10,
                threads =
                    listOf(
                        thread(
                            300,
                            "YCore-Enhanced-",
                            frame("/data/app/~~a/com.yfuse/lib/arm64/libavcodec.so", 0x4567, "ff_hevc_decode", 40),
                            frame("/data/app/~~a/com.yfuse/lib/arm64/libycore_demux.so", 0x8ab, buildId = "0a1b2c"),
                        ),
                    ),
            )

        val report = assertNotNull(parseNativeCrashTombstone(tombstone))

        assertEquals(
            NativePlaybackComponent.YCoreDemux to NativeCrashAttribution.Backtrace,
            report.attributedComponent(),
        )
        assertEquals("SIGSEGV/SEGV_MAPERR", report.signal)
        assertEquals(0x10L, report.faultAddress)
        val summary = report.redactedSummary()
        assertTrue("libavcodec.so+0x4567 (ff_hevc_decode+40)" in summary, summary)
        assertTrue("libycore_demux.so+0x8ab BuildId=0a1b2c" in summary, summary)
        assertFalse("/data/app" in summary)
    }

    @Test
    fun a_text_tombstone_is_read_the_same_way() {
        val tombstone =
            """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            Build fingerprint: 'vendor/device:14/UP1A/1:user/release-keys'
            ABI: 'arm64'
            pid: 4321, tid: 4400, name: YCoreToneMap  >>> com.yfuse <<<
            uid: 10234
            signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x00000077f0000000
            Abort message: 'open https://user:pw@media.example/film.mkv?token=abc failed'
                x0  0000000000000000  x1  0000000000000001

            backtrace:
                  #00 pc 000000000005e0d4  /apex/com.android.runtime/lib64/bionic/libc.so (memcpy+180) (BuildId: aa11)
                  #01 pc 0000000000041a20  /data/app/~~x/com.yfuse/lib/arm64/libc++_shared.so (BuildId: bb22)

            stack:
                     0000007fffffe000  0000000000000000
            --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
            pid: 4321, tid: 4322, name: mpv/demux  >>> com.yfuse <<<
            backtrace:
                  #00 pc 0000000000012345  /data/app/~~x/com.yfuse/lib/arm64/libmpv.so (BuildId: cc33)
            """.trimIndent().encodeToByteArray()

        val report = assertNotNull(parseNativeCrashTombstone(tombstone))

        assertEquals("YCoreToneMap", report.threadName)
        assertEquals(listOf("libc.so", "libc++_shared.so"), report.frames.map(NativeCrashFrame::library))
        assertEquals("memcpy", report.frames.first().function)
        assertEquals(
            NativePlaybackComponent.YCoreDemux to NativeCrashAttribution.ThreadName,
            report.attributedComponent(),
        )
        val summary = report.redactedSummary()
        assertFalse("pw@" in summary || "token=abc" in summary, summary)
    }

    @Test
    fun something_that_is_not_a_tombstone_yields_nothing() {
        assertNull(parseNativeCrashTombstone(ByteArray(64) { 0xFF.toByte() }))
        assertNull(parseNativeCrashTombstone("plain text, no crash here".encodeToByteArray()))
    }

    private class Writer {
        private val bytes = mutableListOf<Byte>()

        fun varint(
            field: Int,
            value: Long,
        ) {
            raw((field.toLong() shl 3) or 0L)
            raw(value)
        }

        fun bytes(
            field: Int,
            value: ByteArray,
        ) {
            raw((field.toLong() shl 3) or 2L)
            raw(value.size.toLong())
            value.forEach(bytes::add)
        }

        fun text(
            field: Int,
            value: String,
        ) = bytes(field, value.encodeToByteArray())

        fun toByteArray(): ByteArray = bytes.toByteArray()

        private fun raw(value: Long) {
            var remaining = value
            while (true) {
                if (remaining and 0x7FL.inv() == 0L) {
                    bytes += remaining.toByte()
                    return
                }
                bytes += ((remaining and 0x7FL) or 0x80L).toByte()
                remaining = remaining ushr 7
            }
        }
    }

    private class Frame(
        val file: String,
        val pc: Long,
        val function: String?,
        val offset: Long,
        val buildId: String?,
    )

    private class Thread(
        val id: Long,
        val name: String,
        val frames: List<Frame>,
    )

    private fun frame(
        file: String,
        pc: Long,
        function: String? = null,
        offset: Long = 0L,
        buildId: String? = null,
    ) = Frame(file, pc, function, offset, buildId)

    private fun thread(
        id: Long,
        name: String,
        vararg frames: Frame,
    ) = Thread(id, name, frames.toList())

    private fun protoTombstone(
        crashingTid: Long,
        threads: List<Thread>,
        signalName: String = "SIGABRT",
        codeName: String = "SI_TKILL",
        faultAddress: Long? = null,
    ): ByteArray {
        val root = Writer()
        root.text(2, "vendor/device:14/UP1A/1:user/release-keys")
        root.varint(5, 4321)
        root.varint(6, crashingTid)
        val signal = Writer()
        signal.varint(1, 11)
        signal.text(2, signalName)
        signal.text(4, codeName)
        if (faultAddress != null) {
            signal.varint(8, 1)
            signal.varint(9, faultAddress)
        }
        root.bytes(10, signal.toByteArray())
        threads.forEach { thread ->
            val body = Writer()
            body.varint(1, thread.id)
            body.text(2, thread.name)
            thread.frames.forEach { frame ->
                val encoded = Writer()
                encoded.varint(1, frame.pc)
                frame.function?.let { encoded.text(4, it) }
                encoded.varint(5, frame.offset)
                encoded.text(6, frame.file)
                frame.buildId?.let { encoded.text(8, it) }
                body.bytes(4, encoded.toByteArray())
            }
            val entry = Writer()
            entry.varint(1, thread.id)
            entry.bytes(2, body.toByteArray())
            root.bytes(16, entry.toByteArray())
        }
        return root.toByteArray()
    }
}
