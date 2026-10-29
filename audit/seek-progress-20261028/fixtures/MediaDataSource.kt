package android.media

/** JVM contract fixture only: no Android extractor or decoder is simulated. */
abstract class MediaDataSource : java.io.Closeable {
    abstract fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    abstract fun getSize(): Long
    @get:JvmName("getHostFixtureSize")
    val size: Long get() = getSize()
}
