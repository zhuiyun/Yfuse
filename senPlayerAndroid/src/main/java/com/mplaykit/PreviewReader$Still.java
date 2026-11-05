package com.mplaykit;

/** Constructor required by JNI registration, even when the still-reader API is unused. */
public final class PreviewReader$Still {
    public final int width, height;
    public final double time, duration, rotation;
    public final int dolbyProfile;
    public final boolean cover;
    public final byte[] rgba, metadata;
    private PreviewReader$Still(int width, int height, double time, double duration,
            double rotation, int dolbyProfile, boolean cover, byte[] rgba, byte[] metadata) {
        this.width = width;
        this.height = height;
        this.time = time;
        this.duration = duration;
        this.rotation = rotation;
        this.dolbyProfile = dolbyProfile;
        this.cover = cover;
        this.rgba = rgba;
        this.metadata = metadata;
    }
}
