package com.mplaykit;

/** JNI callback. Native text can contain authenticated URLs, so it is not logged verbatim. */
public final class Diagnostics {
    private Diagnostics() {}
    private static void receive(int level, byte[] tag, byte[] message) {
        // Playback failures are surfaced through the structured snapshot, without leaking URLs.
    }
}
