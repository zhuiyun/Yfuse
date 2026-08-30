# Yfuse Cast Receiver

This receiver is the evidence boundary for Cast Dolby Vision / Dolby Atmos output. It queries the
Cast device and connected display, validates the requested media types, and emits an output receipt
only after the receiver reaches `PLAYING` for the matching load revision.

The sender/receiver contract also reports queue index/size and active track ids. Android uses the
native Cast media channel for queue load/next/previous and audio/subtitle selection; the custom
namespace is the revision-scoped receipt channel and never upgrades source metadata into output
evidence.

Production receiver URL: <https://zhuiyun.github.io/yfuse-cast/>
Receiver application ID: `E9107559`

The deployed copy lives in the public `zhuiyun/zhuiyun.github.io` repository so this URL remains
available if the main Yfuse repository becomes private. Keep the deployed `index.html` and
`receiver.js` synchronized with this directory when the receiver changes.

To activate it:

1. Register the production URL above as a Custom Web Receiver in the Google Cast Developer Console.
2. Copy the generated eight-character Receiver Application ID.
3. Keep `yfuseCastReceiverApplicationId=E9107559` in the checked-in `gradle.properties` file.
4. Add at least one Android sender application (production package name plus SHA-1) in the Cast
   Developer Console, then publish the receiver. A receiver remaining `Unpublished` is not a
   production pass even when its URL returns HTTP 200.

If that property is removed, the app intentionally uses Google's Default Media Receiver. Playback
still works through the existing H.264/AAC fallback, while Dolby capabilities and output remain
unknown.

## Physical Chromecast acceptance

Run this matrix against application ID `E9107559`; record the Cast device model, firmware, TV/AVR
route and source. Automated tests and Chrome playback are not substitutes.

- Receiver opens from `https://zhuiyun.github.io/yfuse-cast/` and reaches `PLAYING`.
- Pause/resume, merged seek and volume commands are reflected back in sender state.
- Audio and subtitle changes select the intended receiver track; subtitle off clears text tracks.
- A three-item queue supports automatic advance, next and previous without rebuilding local play.
- Stopping Cast hands the confirmed position and play/pause intent back to local playback.
- Wi-Fi loss/session suspension yields one unexpected-disconnect handoff and can reconnect without
  accepting stale receipts from an older revision.
- Dolby Vision and Atmos badges appear only when the current revision returns positive output
  receipts from the actual display/audio chain; unsupported and unknown routes use the safe media
  fallback and show no positive badge.
