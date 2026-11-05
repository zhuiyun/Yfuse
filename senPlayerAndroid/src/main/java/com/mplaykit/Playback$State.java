package com.mplaykit;

/** Native snapshot. Array layout is tied to SenPlayer Android 1.0.0 build 8. */
public final class Playback$State {
    public final long source;
    public final int errorCode, width, height;
    public final String status, error;
    public final double position, duration, cacheDuration;
    public final boolean paused, buffering;

    public Playback$State(long[] flags, double[] numbers, String[] text) {
        if (flags.length < 22 || numbers.length < 3 || text.length < 7) {
            throw new IllegalArgumentException("Unsupported SenPlayer state ABI");
        }
        source = flags[0];
        errorCode = (int) flags[2];
        width = (int) flags[8];
        height = (int) flags[9];
        paused = flags[5] != 0;
        buffering = "buffering".equals(text[0]) || "opening".equals(text[0]) || "loading".equals(text[0]);
        status = text[0];
        error = text[5];
        position = numbers[0];
        duration = numbers[1];
        cacheDuration = numbers[2];
    }
}
