package com.yfuse.core2.android

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class NativeDirectHdr10PlusTest {
    @Test
    fun extractsMetadataFromAnnexBAndLengthPrefixedSamplesWithoutConsumingInput() {
        val metadata = byteArrayOf(0xb5.toByte(), 0x00, 0x3c, 0x00, 0x01, 0x04, 0x55)
        val sei =
            byteArrayOf((39 shl 1).toByte(), 0x01, 0x04, metadata.size.toByte()) +
                metadata +
                byteArrayOf(0x80.toByte())
        val annexB = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sei)
        val lengthPrefixed =
            ByteBuffer.wrap(
                byteArrayOf(0, 0, 0, sei.size.toByte()) + sei,
            )

        assertContentEquals(metadata, extractNativeDirectHdr10PlusPayload(annexB))
        assertContentEquals(metadata, extractNativeDirectHdr10PlusPayload(lengthPrefixed))
        assertContentEquals(byteArrayOf(0, 0, 0, 1), ByteArray(4).also(annexB.duplicate()::get))
    }

    @Test
    fun rejectsNonHdr10PlusSamples() {
        assertNull(extractNativeDirectHdr10PlusPayload(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 2, 1))))
    }
}
