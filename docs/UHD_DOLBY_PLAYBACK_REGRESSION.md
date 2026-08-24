# UHD / Dolby playback regression contract

This document records the failure modes fixed after the 2026-08-20 playback diagnostics and the
remaining evidence gates. It is intentionally stricter than a "video started" check: a fallback that
silently preserves an unsupported Dolby stream or a badge inferred from source metadata is a failure.

## Fixed software regressions

### Dolby Vision profile is unknown before decoder creation

A source may be positively identified as Dolby Vision while Emby/Jellyfin omits `DvProfile` and does
not expose a `dvhe.xx` / `dvh1.xx` tag. This is common enough that an unknown profile must be treated as
Dolby-only until a compatible base layer is positively proven.

Passing behavior:

- P5 is always `needsDolbyCapableDecoder=true`;
- `BlPresentFlag=false` is always Dolby-only;
- compatibility id `0` is always Dolby-only;
- unknown profile is Dolby-only by default;
- an unknown profile may use a non-Dolby base-layer fallback only when compatibility id `1`, `2`, or
  `4` positively proves HDR10, SDR, or HLG compatibility;
- `dvhe`, `dvh1`, `dvav`, and `dva1` codec-tag families remain recognized.

This prevents a Profile 5 stream from being handed to an ordinary HEVC decoder and producing the
classic green/magenta picture without a decoder exception.

### Server fallback must be a real compatibility transcode

The direct -> HLS -> progressive fallback ladder exists to remove a codec/profile the client could not
play. It must not use Emby/Jellyfin auto stream-copy for the failing video or audio stream.

Generated HLS and progressive fallbacks therefore require:

- H.264 video;
- AAC audio;
- explicit transcode container/protocol parameters;
- auto stream-copy disabled;
- video stream-copy disabled;
- audio stream-copy disabled;
- AVC required.

A progressive `.mp4` fallback that still decodes as `video/dolby-vision` / `dvhe.05.xx` is a regression,
not a successful transcode.

### HLS HTTP 200 is not sufficient evidence

Some server/proxy failures return HTTP 200 with HTML or JSON in place of `master.m3u8`. The player now
validates the first response bytes before Media3's HLS parser:

- UTF-8 BOM and leading whitespace are allowed;
- the first meaningful bytes must begin with `#EXTM3U`;
- HTML/JSON/other bodies raise a dedicated invalid-HLS response error;
- content type and a short redacted body preview may be logged;
- access tokens and API keys must never appear in the preview;
- deterministic invalid bodies skip a pointless retry and advance the fallback ladder immediately.

### Failure history survives fallback replacement

`replaceMediaItem()` must not erase the reason the previous stream failed. Diagnostics keep a bounded,
credential-free chain such as:

`direct:decoder_failed -> hls:invalid_hls_signature -> progressive:...`

The chain is reset for a new manual item selection, not for an automatic stream replacement of the same
item.

### Dolby audio badges are output facts

TrueHD/Atmos and E-AC-3 JOC are reported as active only when the current Android route has actually
created a non-PCM encoded AudioTrack. A source track saying "Atmos" is not enough.

Passing behavior:

- HDMI/eARC encoded output may report TrueHD/Atmos when Media3/mpv reports active bitstream output;
- speaker/Bluetooth PCM fallback is valid but must not claim Atmos passthrough;
- audio offload is not mislabeled as HDMI passthrough;
- disabling passthrough clears the output claim.

## mpv Android HDR / Dolby boundary

The permanent Yfuse native package contains mpv `gpu-next`, FFmpeg `dovi_split`, libplacebo
enhancement-layer composition and JNI runtime evidence. It is a client-side Dolby processing path,
not evidence of Android Dolby metadata passthrough.

Rules:

- P7 with an enhancement layer uses the verified mpv `gpu-next` path when the device performance
  budget is sufficient;
- constrained P7 devices set `format=dolbyvision=no:enhancement-layer=no`, which really discards RPU
  and EL before rendering the HDR10 base layer;
- P8.1 uses the platform MediaCodec Dolby route when the current display/decoder chain supports it;
- a display without Dolby Vision uses the HDR10 base layer when available, otherwise client-side SDR
  tone mapping; the server is not asked to decode Dolby media;
- `video-out-params`/gamma labels can describe what mpv rendered but cannot by themselves authorize a
  Dolby Vision output badge;
- Profile 7 EL presence is source evidence only;
- FEL may be claimed only when physical/output evidence proves enhancement-layer composition;
- a successful base-layer picture, Dolby-capable display, decoder name, RPU presence, or EL flag alone
  is insufficient FEL evidence.

## UHD Blu-ray regression corpus

Before a release claim, run at least these cases on physical hardware:

1. MKV DV P5 with server metadata profile present.
2. MKV DV P5 with only `VideoRange=Dolby Vision` and profile unknown.
3. DV P8 with HDR10-compatible base layer.
4. UHD Blu-ray P7 MEL/EL sample with BL + RPU evidence.
5. UHD Blu-ray P7 FEL candidate; expected result remains `NotMeasured` until enhancement composition is
   independently proven.
6. HDR10, HDR10+, and HLG main-feature streams.
7. Local seekable 80-100+ GiB Blu-ray ISO: start, random seek, resume, EOF.
8. Filesystem and persisted-SAF BDMV trees.
9. Remote authenticated ISO with valid 206 ranges, token refresh, 416 EOF, redirect rejection, and
   large offsets.
10. PGS movie subtitles while keeping DirectStream.
11. HDMV root/popup menu, D-pad/select/back, touch, still frame, overlay clear/flush, and failure
    isolation.
12. TrueHD/Atmos and DTS-HD over HDMI/eARC, plus PCM fallback over speaker and Bluetooth.
13. Invalid HLS endpoint returning HTTP 200 + HTML.
14. Invalid HLS endpoint returning HTTP 200 + JSON.
15. Valid HLS with BOM/leading whitespace.
16. Progressive compatibility fallback; decoded video must be AVC/H.264 rather than the original DV
    profile.

## Explicit non-claims

These are not fixed by pretending the app supports them:

- BD-J remains unsupported until a separately verified Android Java/Xlet runtime exists.
- AACS/BD+ commercial-disc decryption is not bundled; legally supplied external components/keys are a
  separate integration boundary.
- Dolby Vision Profile 7 FEL output labels remain evidence-gated even though the native package has
  a verified composition path; only the JNI post-render signal may authorize the label.
- Native ISO/BDMV support is release-supported only when the exact custom libmpv/libbluray AAR builds,
  passes ABI/16-KiB verification, and passes the physical-disc/device corpus.

The safe fallback for an unsupported capability is an explicit compatible route or an explicit failure,
never a green/magenta picture and never an unsupported Dolby/FEL badge.
