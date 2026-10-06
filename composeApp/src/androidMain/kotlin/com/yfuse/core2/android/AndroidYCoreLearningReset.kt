package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.learning.YPlaybackLearningEngine
import com.yfuse.core2.quirk.YCore2FailureLedger

/**
 * Everything YCore has learned about this device's routes: the failure ledger, the quality memory,
 * routes it has seen play, and decoders it has seen refuse a stream.
 *
 * 重置 YCore 学习数据 promises a clean slate; each of these can keep a class of media off hardware
 * decode on its own, so the reset clears all four.
 */
internal fun resetYCoreLearning(context: Context) {
    val appContext = context.applicationContext
    YCore2FailureLedger(
        store = AndroidYCore2FailureStore(appContext),
        nowEpochMs = System::currentTimeMillis,
    ).clearAll()
    YPlaybackLearningEngine(
        store = AndroidYPlaybackLearningStore(appContext),
        nowEpochMs = System::currentTimeMillis,
    ).clearAll()
    AndroidYCoreVerifiedRouteMemory(appContext).clearAll()
    AndroidRuntimeCapabilityRegistry(appContext).clearAll()
}
