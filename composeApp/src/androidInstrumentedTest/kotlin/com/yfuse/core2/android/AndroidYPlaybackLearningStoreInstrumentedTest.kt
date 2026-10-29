package com.yfuse.core2.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.learning.YLearnedRouteAdvice
import com.yfuse.core2.learning.YPlaybackLearningEngine
import com.yfuse.core2.learning.YPlaybackLearningKey
import com.yfuse.core2.learning.YPlaybackLearningRecord
import com.yfuse.core2.learning.YPlaybackLearningStore
import com.yfuse.core2.learning.YPlaybackObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidYPlaybackLearningStoreInstrumentedTest {
    private val key = YPlaybackLearningKey(YPlaybackRoute.NativeDirect, YContainer.Mp4, YVideoCodec.H264, YHdrType.Sdr)
    private val success = YPlaybackObservation(rendered = true, playedDurationMs = 1_000L)

    @Test
    fun another_instance_reset_invalidates_live_advice_and_does_not_restore_old_routes() =
        withIsolatedPreferences { context ->
            val store = AndroidYPlaybackLearningStore(context)
            val live = YPlaybackLearningEngine(store, System::currentTimeMillis)
            repeat(4) { live.record(key, YPlaybackObservation(rendered = false, playedDurationMs = 0L)) }
            assertEquals(YLearnedRouteAdvice.Avoid, live.advice(key))

            YPlaybackLearningEngine(AndroidYPlaybackLearningStore(context), System::currentTimeMillis).clearAll()

            assertEquals(YLearnedRouteAdvice.Allow, live.advice(key))
            val other = key.copy(videoCodec = YVideoCodec.H265)
            assertEquals(1, live.record(other, success).attempts)
            assertEquals(listOf(other), AndroidYPlaybackLearningStore(context).load().map { it.key })
        }

    @Test
    fun concurrent_players_merge_every_observation_without_losing_updates() =
        withIsolatedPreferences { context ->
            val workers = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            try {
                val tasks =
                    List(4) {
                        val store = AndroidYPlaybackLearningStore(context)
                        store.load() // Prime every instance before any writer starts.
                        val engine = YPlaybackLearningEngine(store, System::currentTimeMillis)
                        workers.submit {
                            check(start.await(5, TimeUnit.SECONDS))
                            repeat(25) { engine.record(key, success) }
                        }
                    }
                start.countDown()
                tasks.forEach { it.get(10, TimeUnit.SECONDS) }
                val record = AndroidYPlaybackLearningStore(context).load().single()
                assertEquals(100, record.attempts)
                assertEquals(100, record.successfulAttempts)
                assertEquals(100_000L, record.playedDurationMs)
            } finally {
                start.countDown()
                workers.shutdownNow()
                workers.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

    @Test
    fun reset_waits_for_an_in_flight_merge_then_clears_it_without_dropping_later_observations() =
        withIsolatedPreferences { context ->
            val store = AndroidYPlaybackLearningStore(context)
            val merging = CountDownLatch(1)
            val finishMerge = CountDownLatch(1)
            val resetting = CountDownLatch(1)
            val resetFinished = CountDownLatch(1)
            val blockingStore =
                object : YPlaybackLearningStore by store {
                    override fun update(
                        transform: (List<YPlaybackLearningRecord>) -> List<YPlaybackLearningRecord>,
                    ): List<YPlaybackLearningRecord> =
                        store.update { records ->
                            merging.countDown()
                            check(finishMerge.await(5, TimeUnit.SECONDS))
                            transform(records)
                        }
                }
            val live = YPlaybackLearningEngine(blockingStore, System::currentTimeMillis)
            val reset = YPlaybackLearningEngine(AndroidYPlaybackLearningStore(context), System::currentTimeMillis)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val observation = workers.submit { live.record(key, success) }
                assertTrue(merging.await(5, TimeUnit.SECONDS))
                val clearing =
                    workers.submit {
                        resetting.countDown()
                        reset.clearAll()
                        resetFinished.countDown()
                    }
                assertTrue(resetting.await(5, TimeUnit.SECONDS))
                assertFalse(
                    "Reset must serialize behind the in-flight merge",
                    resetFinished.await(100, TimeUnit.MILLISECONDS),
                )
                finishMerge.countDown()
                observation.get(5, TimeUnit.SECONDS)
                clearing.get(5, TimeUnit.SECONDS)
                assertTrue(store.load().isEmpty())
                assertEquals(1, live.record(key, success).attempts)
                assertEquals(1, AndroidYPlaybackLearningStore(context).load().single().attempts)
            } finally {
                finishMerge.countDown()
                workers.shutdownNow()
                workers.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

    @Test
    fun a_decoded_snapshot_still_expires_while_the_player_remains_alive() =
        withIsolatedPreferences { context ->
            var now = System.currentTimeMillis()
            val store = AndroidYPlaybackLearningStore(context) { now }
            YPlaybackLearningEngine(store, nowEpochMs = { now }).record(key, success)
            assertEquals(1, store.load().size)
            now += 91L * 24 * 60 * 60 * 1_000
            assertTrue(store.load().isEmpty())
        }

    private fun withIsolatedPreferences(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val name = "learning-regression-${UUID.randomUUID()}"
        val context =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    ignored: String,
                    mode: Int,
                ): SharedPreferences = base.getSharedPreferences(name, mode)
            }
        try {
            test(context)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
