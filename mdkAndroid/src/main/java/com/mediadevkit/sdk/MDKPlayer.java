package com.mediadevkit.sdk;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Android Java facade for libmdk.
 *
 * The surface and basic playback shape follows the official wang-bin/mdk-android wrapper. Yfuse
 * adds the controls required by its shared player UI: explicit release, playback rate, decoder
 * preference, track selection, media status and aspect-ratio mode.
 */
public final class MDKPlayer implements SurfaceHolder.Callback, AutoCloseable {
    public static final int STATE_STOPPED = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_PAUSED = 2;

    public static final int MEDIA_TYPE_VIDEO = 0;
    public static final int MEDIA_TYPE_AUDIO = 1;
    public static final int MEDIA_TYPE_SUBTITLE = 3;

    public static final int STATUS_LOADING = 1 << 1;
    public static final int STATUS_LOADED = 1 << 2;
    public static final int STATUS_STALLED = 1 << 3;
    public static final int STATUS_BUFFERING = 1 << 4;
    public static final int STATUS_END = 1 << 6;
    public static final int STATUS_SEEKING = 1 << 7;
    public static final int STATUS_PREPARED = 1 << 8;
    public static final int STATUS_INVALID = 1 << 31;

    private long nativePtr;
    private SurfaceHolder surfaceHolder;

    public MDKPlayer() {
        nativePtr = nativeCreate();
    }

    public synchronized void setMedia(String url) {
        if (nativePtr != 0) nativeSetMedia(nativePtr, url);
    }

    public synchronized void setState(int state) {
        if (nativePtr != 0) nativeSetState(nativePtr, state);
    }

    public synchronized int state() {
        return nativePtr == 0 ? STATE_STOPPED : nativeState(nativePtr);
    }

    public synchronized long position() {
        return nativePtr == 0 ? 0 : nativePosition(nativePtr);
    }

    public synchronized long duration() {
        return nativePtr == 0 ? 0 : nativeDuration(nativePtr);
    }

    /** Duration in milliseconds of packets buffered ahead of the current position. */
    public synchronized long bufferedDuration() {
        return nativePtr == 0 ? 0 : nativeBufferedDuration(nativePtr);
    }

    public synchronized int mediaStatus() {
        return nativePtr == 0 ? STATUS_INVALID : nativeMediaStatus(nativePtr);
    }

    public synchronized int videoHeight() {
        return nativePtr == 0 ? 0 : nativeVideoHeight(nativePtr);
    }

    public synchronized void seek(long positionMs) {
        if (nativePtr != 0) nativeSeek(nativePtr, positionMs);
    }

    public synchronized void setPlaybackRate(float rate) {
        if (nativePtr != 0) nativeSetPlaybackRate(nativePtr, rate);
    }

    public synchronized float playbackRate() {
        return nativePtr == 0 ? 1f : nativePlaybackRate(nativePtr);
    }

    /** 0 = hardware first, 1 = software, 2 = automatic. Call before setMedia(). */
    public synchronized void setDecoderMode(int mode) {
        if (nativePtr != 0) nativeSetDecoderMode(nativePtr, mode);
    }

    /** Sets an MDK player property such as avio.user_agent before setMedia(). */
    public synchronized void setProperty(String name, String value) {
        if (nativePtr != 0) nativeSetProperty(nativePtr, name, value);
    }

    public synchronized void setFill(boolean fill) {
        if (nativePtr != 0) nativeSetFill(nativePtr, fill);
    }

    /**
     * Track rows are encoded as `ordinal`, `language`, `title`, `selected`, separated by U+001F.
     * Track ordinals are the values accepted by setActiveTrack().
     */
    public synchronized String[] tracks(int mediaType) {
        return nativePtr == 0 ? new String[0] : nativeTracks(nativePtr, mediaType);
    }

    /** Passing a negative ordinal disables all tracks of that type. */
    public synchronized void setActiveTrack(int mediaType, int ordinal) {
        if (nativePtr != 0) nativeSetActiveTrack(nativePtr, mediaType, ordinal);
    }

    public synchronized void setSurfaceView(SurfaceView view) {
        SurfaceHolder next = view == null ? null : view.getHolder();
        if (surfaceHolder == next) return;
        if (surfaceHolder != null) surfaceHolder.removeCallback(this);
        detachSurface();
        surfaceHolder = next;
        if (surfaceHolder != null) {
            surfaceHolder.addCallback(this);
            Surface surface = surfaceHolder.getSurface();
            if (surface != null && surface.isValid()) {
                nativeSetSurface(nativePtr, surface, -1, -1);
            }
        }
    }

    @Override
    public synchronized void surfaceCreated(SurfaceHolder holder) {
        if (nativePtr != 0) nativeSetSurface(nativePtr, holder.getSurface(), -1, -1);
    }

    @Override
    public synchronized void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height
    ) {
        if (nativePtr != 0) nativeSetSurface(nativePtr, holder.getSurface(), width, height);
    }

    @Override
    public synchronized void surfaceDestroyed(SurfaceHolder holder) {
        detachSurface();
    }

    private void detachSurface() {
        if (nativePtr != 0) nativeSetSurface(nativePtr, null, 0, 0);
    }

    @Override
    public synchronized void close() {
        if (nativePtr == 0) return;
        if (surfaceHolder != null) {
            surfaceHolder.removeCallback(this);
            surfaceHolder = null;
        }
        nativeSetSurface(nativePtr, null, 0, 0);
        nativeDestroy(nativePtr);
        nativePtr = 0;
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long ptr);
    private static native void nativeSetMedia(long ptr, String url);
    private static native void nativeSetState(long ptr, int state);
    private static native int nativeState(long ptr);
    private static native long nativePosition(long ptr);
    private static native long nativeDuration(long ptr);
    private static native long nativeBufferedDuration(long ptr);
    private static native int nativeMediaStatus(long ptr);
    private static native int nativeVideoHeight(long ptr);
    private static native void nativeSeek(long ptr, long positionMs);
    private static native void nativeSetPlaybackRate(long ptr, float rate);
    private static native float nativePlaybackRate(long ptr);
    private static native void nativeSetDecoderMode(long ptr, int mode);
    private static native void nativeSetProperty(long ptr, String name, String value);
    private static native void nativeSetFill(long ptr, boolean fill);
    private static native String[] nativeTracks(long ptr, int mediaType);
    private static native void nativeSetActiveTrack(long ptr, int mediaType, int ordinal);
    private static native void nativeSetSurface(
            long ptr,
            Surface surface,
            int width,
            int height
    );

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError ignored) {
        }
        System.loadLibrary("ffmpeg");
        System.loadLibrary("mdk");
        System.loadLibrary("yfuse-mdk-jni");
    }
}
