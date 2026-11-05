package com.mplaykit;

/** Raw seconds and range pairs. No synthetic contiguous range is added here. */
public final class Playback$Progress {
    public final long source;
    public final double position, duration, bufferedEnd;
    public final double[] ranges;
    public Playback$Progress(long[] flags, double[] numbers, double[] ranges, String[] text) {
        if (flags.length < 1 || numbers.length < 6 || ranges.length % 2 != 0) {
            throw new IllegalArgumentException("Unsupported SenPlayer progress ABI");
        }
        source = flags[0];
        position = numbers[0];
        duration = numbers[1];
        bufferedEnd = numbers[4];
        this.ranges = ranges.clone();
    }
}
