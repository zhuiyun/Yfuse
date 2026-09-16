# Anime4K and Jellyfin 12 integration

Base: `086921cf245b2f7028746859bd50ac068e593d51` (master).

## Anime4K

- Opt-in global preference in advanced playback settings and the player popup; takes effect on the next playback/decoder configuration.
- Light (1× working grid), Balanced (up to 1.5×), Quality (up to 2×). Original six-pass edge refinement from bloc97/Anime4K commit `7684e9586f8dcc738af08a1cdceb024cc184f426`, not the CNN model. Source shader and MIT notice ship in both phone and TV assets.
- YCore 2 NativeDirect and NativeEnhanced MediaCodec-to-Surface paths. Frames stay on GPU via SurfaceTexture/OpenGL ES 3. Existing audio and remote direct-play paths remain in place.
- First implementation accepts SDR sources up to 1920×1080 pixels without rotation; skips encrypted media, Dolby Vision and HDR. Unsupported devices retain direct Surface output. Exo, MPV, MDK, Vulkan HDR and software-decoded output do not apply this filter.
- Explicit processing disables the incompatible hardware tunnel route for eligible source hints. It also prevents decoder handoff from reusing a decoder with the previous effect setting.
- Intermediate textures are bounded to 64 MiB (this is not the whole process memory budget). RG16F preserves signed gradients. Sustained slow rendering reduces the working resolution; repeated pressure or severe Android thermal status bypasses enhancement. Bypass still presents through the existing texture surface until the session ends.
- EGL/codec setup failure retries direct Surface output. Runtime EGL failure switches the codec back to the display Surface.
- Render evidence is published only after swapping the output frame; timestamp bookkeeping is bounded and flushed on seek. Actual processing/bypass appears in playback diagnostics.

## Jellyfin 12

Official references:
- https://jellyfin.org/posts/jellyfin-release-12.0/
- https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f

- Send `Authorization: MediaBrowser ... Token="..."` for API calls; login/discovery sends modern client identity without a token. Keep older Emby identity headers with the same session token for backwards compatibility.
- Preserve explicit proxy Basic/Bearer authorization and supply the Jellyfin token as `ApiKey` in that case.
- URL-only consumers receive modern `ApiKey` alongside the identical legacy `api_key` for old Emby compatibility. Covers images, direct/progressive/HLS addresses, subtitles, trickplay and remote-disc headers. Never add credentials to an independently signed CDN address.
- Redact modern URL token spelling from player/HLS logs and exclude it from cache identity.
- Password login/public-user discovery and Quick Connect repair removed `/emby` or `/mediabrowser` suffixes only if the original public endpoint returns 404/405 and the replacement positively identifies Jellyfin. Working reverse-proxy base paths are preserved. Existing saved obsolete aliases can be repaired through re-login/edit; this change does not blindly rewrite stored routes.
- Quick Connect already uses POST; server detection uses ProductName, not a 10.x version prefix. Public-user HasPassword is not used to bypass password input.
- Specify recursive intent for collection child listings and explicit-ID saved lists. Keep existing episode MediaSourceId selection, playback reporting and subtitle paths.

## Validation and remaining device checks

`python3 scripts/test-anime4k-shaders.py` uses a real headless EGL driver to compile/link all six GLES shaders and verify signed RG16F storage. It does not simulate Android codecs.

Host regression coverage includes modern API/proxy authentication, URL encoding, server-prefix repair, CDN isolation, preference persistence and frame coalescing/seek epochs.

Device validation is still required for MediaCodec + SurfaceTexture on Qualcomm/Mali, thermal fallback, pause/seek, app background/foreground, output-surface replacement and next episode. Jellyfin 12 server end-to-end testing requires a test server. No claim of device or live-server testing is made by host compilation alone.

## Verified on 2026-09-16

- `:phoneShared:compileAndroidMain`: passed with Android API 37.0 and Kotlin 2.4.20.
- `:phoneShared:testAndroidHostTest`: 69 tests, 0 failures, 0 errors, 0 skipped across Jellyfin12CompatibilityTest, EmbyStreamTest, EmbyImagesTest, HttpClientFactoryTest, PlaybackPreferencesTest, Anime4KFrameLedgerTest and ImageCacheKeyTest.
- Headless EGL: six shader compile/link checks and signed floating-point framebuffer check passed.
- Changed Kotlin files: ktlint passed; `git diff --check` passed.
- No APK was packaged; no physical device or live Jellyfin server was exercised.
