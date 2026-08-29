# Yfuse Cast Receiver

This receiver is the evidence boundary for Cast Dolby Vision / Dolby Atmos output. It queries the
Cast device and connected display, validates the requested media types, and emits an output receipt
only after the receiver reaches `PLAYING` for the matching load revision.

To activate it:

1. Host `index.html` and `receiver.js` on HTTPS.
2. Register that URL as a Custom Web Receiver in the Google Cast Developer Console.
3. Build the Android app with `-PyfuseCastReceiverApplicationId=XXXXXXXX`.

Without that property the app intentionally uses Google's Default Media Receiver. Playback still
works through the existing H.264/AAC fallback, while Dolby capabilities and output remain unknown.
