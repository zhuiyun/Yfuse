package android.content

open class Context {
    open fun getSystemService(name: String): Any? = null
    open val applicationContext: Context get() = this
    open val cacheDir: java.io.File get() = error("Tests must supply their own cache directory")
    companion object { const val ACTIVITY_SERVICE = "activity" }
}
