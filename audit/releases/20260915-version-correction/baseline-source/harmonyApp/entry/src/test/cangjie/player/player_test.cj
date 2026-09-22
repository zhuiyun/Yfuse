package tests.player

import std.collection.*
import std.unittest.*
import std.unittest.testmacro.*
import com.yfuse.harmony.entry.player.*

// Route, subtitle, output and disc decisions are pure functions with no HarmonyOS dependency, so
// the host compiler can execute them. These are the rules every release claim rests on.

private func routeName(value: RouteDecision): String {
    return match (value) {
        case RouteDecision.System => "system"
        case RouteDecision.Native => "native"
        case RouteDecision.Unsupported(_) => "unsupported"
    }
}

private func subtitleName(value: SubtitleRouteDecision): String {
    return match (value) {
        case SubtitleRouteDecision.SystemTrack => "system"
        case SubtitleRouteDecision.AppTextOverlay => "overlay"
        case SubtitleRouteDecision.NativeLibass => "libass"
        case SubtitleRouteDecision.NativeBitmap => "bitmap"
        case SubtitleRouteDecision.Unsupported(_) => "unsupported"
    }
}

private func outputName(value: OutputDecision): String {
    return match (value) {
        case OutputDecision.Direct => "direct"
        case OutputDecision.DecodeToPcm => "pcm"
        case OutputDecision.ToneMapToSdr => "tonemap"
        case OutputDecision.ServerTranscode => "transcode"
        case OutputDecision.Unsupported(_) => "unsupported"
    }
}

private func discName(value: DiscPreflight): String {
    return match (value) {
        case DiscPreflight.Ready => "ready"
        case DiscPreflight.NeedsDownload(_) => "download"
        case DiscPreflight.Unsupported(_) => "unsupported"
    }
}

private const everything: UInt64 = 0xFFFFFFFF

@Test
func system_route_wins_when_it_satisfies_requirements(): Unit {
    let requirements = PlaybackRequirements()
    @Expect(routeName(decidePlaybackRoute(requirements, everything, everything, true)), "system")
}

@Test
func native_route_is_refused_without_a_surface(): Unit {
    let requirements = PlaybackRequirements()
    requirements.customDemux = true
    let systemOnly = CAP_HARDWARE_VIDEO | CAP_REMOTE_URL
    @Expect(routeName(decidePlaybackRoute(requirements, systemOnly, everything, false)), "unsupported")
    @Expect(routeName(decidePlaybackRoute(requirements, systemOnly, everything, true)), "native")
}

@Test
func optical_disc_without_a_surface_says_why(): Unit {
    let requirements = PlaybackRequirements()
    requirements.opticalDisc = true
    let decision = decidePlaybackRoute(requirements, CAP_HARDWARE_VIDEO, everything, false)
    let explained = match (decision) {
        case RouteDecision.Unsupported(reason) => reason.contains("ISO/BDMV")
        case _ => false
    }
    @Expect(explained, true)
}

@Test
func authorization_and_drm_failures_never_switch_engines(): Unit {
    @Expect(mayAutomaticHandover("authorization"), false)
    @Expect(mayAutomaticHandover("drm"), false)
    @Expect(mayAutomaticHandover("decoder"), true)
}

@Test
func text_subtitles_pick_overlay_only_when_external(): Unit {
    @Expect(subtitleName(subtitleRoute(SubtitleFormat.Srt, false, 0)), "system")
    @Expect(subtitleName(subtitleRoute(SubtitleFormat.Srt, true, 0)), "overlay")
    @Expect(subtitleName(subtitleRoute(SubtitleFormat.WebVtt, true, 0)), "overlay")
}

@Test
func styled_and_bitmap_subtitles_fail_closed_without_native_window(): Unit {
    @Expect(subtitleName(subtitleRoute(SubtitleFormat.Ass, false, CAP_EXTERNAL_SUBTITLE)), "unsupported")
    @Expect(subtitleName(subtitleRoute(SubtitleFormat.Pgs, false, CAP_BITMAP_SUBTITLE)), "unsupported")
    @Expect(
        subtitleName(subtitleRoute(SubtitleFormat.Ass, false, CAP_EXTERNAL_SUBTITLE | CAP_NATIVE_WINDOW)),
        "libass"
    )
    @Expect(
        subtitleName(subtitleRoute(SubtitleFormat.Pgs, false, CAP_BITMAP_SUBTITLE | CAP_NATIVE_WINDOW)),
        "bitmap"
    )
    @Expect(subtitleName(subtitleRoute(SubtitleFormat.Unknown, false, everything)), "unsupported")
}

@Test
func hdr_falls_back_through_tone_map_then_transcode(): Unit {
    let capable = OutputCapabilities()
    capable.hdr10 = true
    @Expect(outputName(dynamicRangeDecision(DynamicRange.Hdr10, capable, 0)), "direct")

    let plain = OutputCapabilities()
    @Expect(outputName(dynamicRangeDecision(DynamicRange.Hdr10, plain, CAP_HDR_OUTPUT)), "tonemap")
    @Expect(outputName(dynamicRangeDecision(DynamicRange.Hdr10, plain, 0)), "transcode")
    @Expect(outputName(dynamicRangeDecision(DynamicRange.Sdr, plain, 0)), "direct")
}

@Test
func dolby_vision_needs_both_the_sink_and_the_decoder(): Unit {
    let sinkOnly = OutputCapabilities()
    sinkOnly.dolbyVision = true
    @Expect(outputName(dynamicRangeDecision(DynamicRange.DolbyVision, sinkOnly, 0)), "transcode")
    @Expect(
        outputName(dynamicRangeDecision(DynamicRange.DolbyVision, sinkOnly, CAP_DOLBY_VISION_OUTPUT)),
        "direct"
    )
}

@Test
func passthrough_is_only_direct_for_a_supported_codec(): Unit {
    let output = OutputCapabilities()
    output.encodedEac3 = true
    @Expect(outputName(audioDecision("eac3", output, true)), "direct")
    @Expect(outputName(audioDecision("truehd", output, true)), "pcm")
    @Expect(outputName(audioDecision("eac3", output, false)), "pcm")
}

@Test
func an_output_badge_never_claims_active_without_evidence(): Unit {
    let unverified = OutputEvidence("Dolby Vision", "hw", "gpu", "hdmi", false)
    @Expect(verifiedOutputBadge(unverified).contains("未验证输出"), true)

    let missingSink = OutputEvidence("Dolby Vision", "hw", "gpu", "", true)
    @Expect(verifiedOutputBadge(missingSink).contains("未验证输出"), true)

    let verified = OutputEvidence("Dolby Vision", "hw", "gpu", "hdmi", true)
    @Expect(verifiedOutputBadge(verified).contains("已输出"), true)
}

@Test
func disc_preflight_names_the_missing_piece(): Unit {
    let source = DiscSource("d1", DiscSourceKind.RemoteIso, "https://example/movie.iso")
    let libraries = CAP_OPTICAL_DISC | CAP_CUSTOM_DEMUX
    @Expect(discName(preflightDisc(source, 0, true)), "unsupported")
    @Expect(discName(preflightDisc(source, libraries, true)), "unsupported")
    @Expect(discName(preflightDisc(source, libraries | CAP_NATIVE_WINDOW, false)), "download")
    @Expect(discName(preflightDisc(source, libraries | CAP_NATIVE_WINDOW, true)), "ready")
}

@Test
func skip_controller_asks_once_and_remembers(): Unit {
    let markers = ArrayList<SkipMarker>()
    markers.add(SkipMarker("intro", 1000, 5000))

    let asking = SkipController()
    asking.introMode = SkipMode.Ask
    let offered = match (asking.actionAt(2000, markers)) {
        case Some((kind, endMs, automatic)) => kind == "intro" && endMs == 5000 && !automatic
        case None => false
    }
    @Expect(offered, true)

    let automatic = SkipController()
    automatic.introMode = SkipMode.Automatic
    let first = match (automatic.actionAt(2000, markers)) {
        case Some((_, _, auto)) => auto
        case None => false
    }
    @Expect(first, true)

    let second = match (automatic.actionAt(2500, markers)) {
        case Some(_) => true
        case None => false
    }
    @Expect(second, false)
}

@Test
func skip_off_never_offers_anything(): Unit {
    let markers = ArrayList<SkipMarker>()
    markers.add(SkipMarker("credits", 1000, 5000))
    let controller = SkipController()
    controller.creditsMode = SkipMode.Off
    let offered = match (controller.actionAt(2000, markers)) {
        case Some(_) => true
        case None => false
    }
    @Expect(offered, false)
}

@Test
func sleep_timer_clamps_and_fires(): Unit {
    let timer = SleepTimer()
    timer.startAfter(0, 500)
    @Expect(timer.shouldStop(239 * 60000, false), false)
    @Expect(timer.shouldStop(240 * 60000, false), true)

    let endOfEpisode = SleepTimer()
    endOfEpisode.mode = SleepTimerMode.EndOfEpisode
    @Expect(endOfEpisode.shouldStop(0, false), false)
    @Expect(endOfEpisode.shouldStop(0, true), true)
}

@Test
func dual_subtitles_resolve_both_tracks_at_a_position(): Unit {
    let state = DualSubtitleState()
    state.primary.add(SubtitleCue("p1", 1000, 3000, text: "primary"))
    state.secondary.add(SubtitleCue("s1", 2000, 4000, text: "secondary"))

    let (first, second) = state.visibleAt(2500)
    @Expect(first.size, 1)
    @Expect(second.size, 1)

    let (onlyPrimary, none) = state.visibleAt(1500)
    @Expect(onlyPrimary.size, 1)
    @Expect(none.size, 0)
}

@Test
func subtitle_offset_is_clamped_to_thirty_seconds(): Unit {
    let state = DualSubtitleState()
    state.setOffset(90_000)
    @Expect(state.offsetMs, 30_000)
    state.setOffset(-90_000)
    @Expect(state.offsetMs, -30_000)
    state.setOffset(1_500)
    @Expect(state.offsetMs, 1_500)
}
