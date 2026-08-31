package com.yfuse.core2.android

import android.media.AudioFormat
import com.yfuse.core2.api.YDolbyAtmosOutputMode
import com.yfuse.core2.capability.YAudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAudioRouteEvidenceTest {
    @Test
    fun `route evidence needs clock progress after every route change`() {
        var nowNs = 100L
        val progress = AndroidRoutedOutputProgress(staleAfterNs = 50L, nowNs = { nowNs })

        assertEquals(false, progress.observe(1, YAudioClockSnapshot(10, 100), playing = true))
        nowNs = 110L
        assertEquals(false, progress.observe(1, YAudioClockSnapshot(10, 200), playing = true))
        nowNs = 120L
        assertEquals(true, progress.observe(1, YAudioClockSnapshot(11, 300), playing = true))
        nowNs = 180L
        assertEquals(false, progress.observe(1, YAudioClockSnapshot(11, 350), playing = true))
        nowNs = 190L
        assertEquals(true, progress.observe(1, YAudioClockSnapshot(12, 375), playing = true))
        nowNs = 200L
        assertEquals(false, progress.observe(2, YAudioClockSnapshot(12, 400), playing = true))
        nowNs = 210L
        assertEquals(true, progress.observe(2, YAudioClockSnapshot(13, 500), playing = true))

        progress.reset()
        nowNs = 220L
        assertEquals(false, progress.observe(2, YAudioClockSnapshot(14, 600), playing = true))
    }

    @Test
    fun `JOC needs the exact active routed-device encoding`() {
        val route =
            AndroidAudioRouteEvidence(
                label = "HDMI",
                verified = true,
                encodings = setOf(AudioFormat.ENCODING_E_AC3_JOC),
            )

        assertEquals(
            YDolbyAtmosOutputMode.Eac3JocPassthrough,
            resolveDolbyAtmosOutputMode(
                sourceCodec = YAudioCodec.Eac3Joc,
                sinkCodec = YAudioCodec.Eac3Joc,
                outputAdvancing = true,
                route = route,
                declaredExactTransport = true,
            ),
        )
        assertEquals(
            YDolbyAtmosOutputMode.CarrierOnly,
            resolveDolbyAtmosOutputMode(
                sourceCodec = YAudioCodec.Eac3Joc,
                sinkCodec = YAudioCodec.Eac3Joc,
                outputAdvancing = true,
                route = route.copy(encodings = setOf(AudioFormat.ENCODING_E_AC3)),
                declaredExactTransport = true,
            ),
        )
    }

    @Test
    fun `TrueHD carrier is not promoted to Atmos by encoding support alone`() {
        val route =
            AndroidAudioRouteEvidence(
                label = "HDMI eARC",
                verified = true,
                encodings = setOf(AudioFormat.ENCODING_DOLBY_TRUEHD),
            )

        assertEquals(
            YDolbyAtmosOutputMode.TrueHdCarrierPassthrough,
            resolveDolbyAtmosOutputMode(
                sourceCodec = YAudioCodec.TrueHdAtmos,
                sinkCodec = YAudioCodec.TrueHdAtmos,
                outputAdvancing = true,
                route = route,
                declaredExactTransport = true,
            ),
        )
        assertEquals(
            YDolbyAtmosOutputMode.TrueHdAtmosPassthrough,
            resolveDolbyAtmosOutputMode(
                sourceCodec = YAudioCodec.TrueHdAtmos,
                sinkCodec = YAudioCodec.TrueHdAtmos,
                outputAdvancing = true,
                route = route,
                declaredExactTransport = true,
                independentTrueHdAtmosSinkEvidence = true,
            ),
        )
    }

    @Test
    fun `spatialized PCM is tied to an Atmos source`() {
        val route = AndroidAudioRouteEvidence(label = "蓝牙音频", verified = true)
        assertEquals(
            YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm,
            resolveDolbyAtmosOutputMode(
                sourceCodec = YAudioCodec.Eac3Joc,
                sinkCodec = null,
                outputAdvancing = true,
                route = route,
                declaredExactTransport = false,
                spatializedPcm = true,
            ),
        )
        assertEquals(
            YDolbyAtmosOutputMode.None,
            resolveDolbyAtmosOutputMode(
                sourceCodec = YAudioCodec.Aac,
                sinkCodec = null,
                outputAdvancing = true,
                route = route,
                declaredExactTransport = false,
                spatializedPcm = true,
            ),
        )
    }
}
