# Yfuse Cast Receiver

This receiver is the evidence boundary for Cast Dolby Vision / Dolby Atmos output. It queries the
Cast device and connected display, validates the requested media types, and emits an output receipt
only after the receiver reaches `PLAYING` for the matching load revision.

Production receiver URL: <https://zhuiyun.github.io/yfuse-cast/>

The deployed copy lives in the public `zhuiyun/zhuiyun.github.io` repository so this URL remains
available if the main Yfuse repository becomes private. Keep the deployed `index.html` and
`receiver.js` synchronized with this directory when the receiver changes.

To activate it:

1. Register the production URL above as a Custom Web Receiver in the Google Cast Developer Console.
2. Copy the generated eight-character Receiver Application ID.
3. Build the Android app with `-PyfuseCastReceiverApplicationId=XXXXXXXX`.

Without that property the app intentionally uses Google's Default Media Receiver. Playback still
works through the existing H.264/AAC fallback, while Dolby capabilities and output remain unknown.
