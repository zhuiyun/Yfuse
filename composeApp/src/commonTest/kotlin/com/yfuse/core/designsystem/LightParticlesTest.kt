package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightParticlesTest {
    @Test
    fun fixedCapacityAndWindowBudgetHoldUnderBursts() {
        val budget = LightParticleBudget()
        val pools = List(20) { LightParticlePool(budget, 32) }
        repeat(120) {
            pools.forEach { it.emit(LightEffect.Trail, 30f, 20f, 60f, 40f, 1f, true) }
            assertTrue(budget.active <= 32)
            pools.forEach { pool ->
                assertTrue(pool.active <= pool.capacity)
                pool.advance(0.016f)
            }
        }
        pools.forEach { it.clear() }
        assertEquals(0, budget.active)
    }

    @Test
    fun everyEffectExpiresWithoutAnotherInputOrIdleClock() {
        for (effect in LightEffect.entries) {
            val budget = LightParticleBudget()
            val pool = LightParticlePool(budget, 64)
            assertTrue(pool.emit(effect, 40f, 24f, 80f, 48f, 2f, false))
            pool.advance(0.5f)
            assertEquals(0, pool.active)
            assertEquals(0, budget.active)
        }
    }

    @Test
    fun everyStyleExpiresAndStaysInsideTheBudget() {
        for (style in ParticleStyle.entries) {
            for (effect in LightEffect.entries) {
                val budget = LightParticleBudget()
                val pool = LightParticlePool(budget, 64)
                assertTrue(pool.emit(effect, 40f, 24f, 80f, 48f, 2f, true, 0.5f, -0.5f, style))
                assertTrue(budget.active <= 12)
                repeat(8) {
                    pool.advance(0.03f)
                    for (i in 0 until pool.capacity) {
                        if (pool.alpha[i] <= 0f) continue
                        assertTrue(pool.x[i].isFinite() && pool.y[i].isFinite(), "$style $effect")
                        assertTrue(pool.radius[i] > 0f)
                    }
                }
                pool.advance(0.5f)
                assertEquals(0, pool.active, "$style $effect")
                assertEquals(0, budget.active)
            }
        }
    }

    @Test
    fun orbitAndFlowMoveOffTheStraightLineStardustKeeps() {
        fun midpoint(style: ParticleStyle): Pair<Float, Float> {
            val pool = LightParticlePool(LightParticleBudget(), 64)
            pool.emit(LightEffect.Converge, 50f, 50f, 100f, 100f, 1f, false, style = style)
            pool.advance(0.1f)
            return pool.x[0] to pool.y[0]
        }
        val straight = midpoint(ParticleStyle.Stardust)
        val orbit = midpoint(ParticleStyle.Orbit)
        val flow = midpoint(ParticleStyle.Flow)
        assertTrue(straight != orbit)
        assertTrue(straight != flow)
        assertTrue(orbit != flow)
    }

    @Test
    fun badDimensionsAndRepeatedEventsDoNotConsumeBudget() {
        val budget = LightParticleBudget()
        val pool = LightParticlePool(budget, 64)
        assertFalse(pool.emit(LightEffect.Node, Float.NaN, 0f, 0f, 0f, 1f, false))
        assertEquals(0, budget.active)
        assertTrue(pool.emit(LightEffect.Node, 10f, 10f, 20f, 20f, 1f, false))
        val count = budget.active
        repeat(100) { assertFalse(pool.emit(LightEffect.Node, 10f, 10f, 20f, 20f, 1f, false)) }
        assertEquals(count, budget.active)
        pool.clear()
        pool.clear()
        assertEquals(0, budget.active)
    }

    @Test
    fun backgroundSizedTimeGapExpiresInsteadOfReplaying() {
        val budget = LightParticleBudget()
        val pool = LightParticlePool(budget, 64)
        pool.emit(LightEffect.Converge, 20f, 20f, 40f, 40f, 1f, true)
        pool.advance(60f)
        assertEquals(0, budget.active)
        assertTrue(pool.emit(LightEffect.Converge, 20f, 20f, 40f, 40f, 1f, true))
        pool.clear()
        assertEquals(0, budget.active)
    }

    @Test
    fun appearanceCannotReplayAfterFocusOrSettingChange() {
        val gate = LightAppearanceGate()
        assertTrue(gate.consume())
        repeat(100) { assertFalse(gate.consume()) }
    }
}
