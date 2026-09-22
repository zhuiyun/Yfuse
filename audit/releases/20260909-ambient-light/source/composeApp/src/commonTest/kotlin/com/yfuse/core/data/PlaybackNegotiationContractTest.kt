package com.yfuse.core.data

import com.yfuse.core.data.dto.PlaybackInfoRequestDto
import com.yfuse.core.data.dto.YFUSE_LOCAL_DECODE_MAX_AUDIO_CHANNELS
import com.yfuse.core.data.dto.YFUSE_MAX_STREAMING_BITRATE_BPS
import com.yfuse.core.model.SavedServer
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackNegotiationContractTest {
    private val server = SavedServer("id", "http://host:8096", "yfuse", "user", "server", "token")
    private val codec = Json { ignoreUnknownKeys = true }

    @Test
    fun stereo_phone_route_does_not_force_server_downmix_or_transcode() =
        runTest {
            val discovered = PlaybackDeviceCapabilities.conservative().copy(maxAudioChannels = 2)
            val repo =
                testRepo(
                    capabilitiesProvider = PlaybackDeviceCapabilitiesProvider { discovered },
                    audioPassthroughEnabled = { false },
                ) { request ->
                    val posted =
                        codec.decodeFromString<PlaybackInfoRequestDto>(
                            request.body.toByteArray().decodeToString(),
                        )
                    val direct = posted.DeviceProfile.DirectPlayProfiles.single()

                    assertEquals(YFUSE_LOCAL_DECODE_MAX_AUDIO_CHANNELS, posted.MaxAudioChannels)
                    assertEquals(YFUSE_MAX_STREAMING_BITRATE_BPS, posted.MaxStreamingBitrate)
                    assertEquals(YFUSE_MAX_STREAMING_BITRATE_BPS, posted.DeviceProfile.MaxStreamingBitrate)
                    assertTrue("hevc" in direct.VideoCodec.split(','))
                    assertTrue("truehd" in direct.AudioCodec.split(','))
                    assertTrue("eac3" in direct.AudioCodec.split(','))
                    json("""{"MediaSources":[]}""")
                }

            assertTrue(repo.playbackInfo(server, "movie", playSessionId = "session").isSuccess)
        }

    @Test
    fun dolby_only_source_disables_direct_routes_when_device_has_no_dolby_output() =
        runTest {
            val repo =
                testRepo { request ->
                    val posted =
                        codec.decodeFromString<PlaybackInfoRequestDto>(
                            request.body.toByteArray().decodeToString(),
                        )

                    assertFalse(posted.EnableDirectPlay)
                    assertFalse(posted.EnableDirectStream)
                    assertFalse(posted.AllowVideoStreamCopy)
                    assertTrue(posted.EnableTranscoding)
                    json(
                        """{"MediaSources":[{"Id":"dv-p5","SupportsDirectPlay":false,"SupportsTranscoding":true,"TranscodingUrl":"/Videos/movie/master.m3u8"}]}""",
                    )
                }

            val result =
                repo.playbackInfo(
                    server = server,
                    itemId = "movie",
                    mediaSourceId = "dv-p5",
                    playSessionId = "session",
                    sourceRequiresDolbyDecoder = true,
                )

            assertEquals(
                "/Videos/movie/master.m3u8",
                result.getOrThrow().MediaSources.single().TranscodingUrl,
            )
        }
}
