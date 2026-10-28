package android.os

/** Host test threads have no Android looper. */
class Looper private constructor() {
    companion object {
        @JvmStatic fun getMainLooper(): Looper? = null
        @JvmStatic fun myLooper(): Looper? = null
    }
}
