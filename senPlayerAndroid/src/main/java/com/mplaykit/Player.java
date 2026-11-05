package com.mplaykit;

import android.os.Build;
import android.view.Display;
import android.view.Surface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal interoperability wrapper for the supplied binary, not an official SenPlayer SDK.
 * The native binary and its access checks are not modified. No runtime permit is fabricated.
 * Call from one worker; close never races with a query or command.
 */
public final class Player implements AutoCloseable {
    private long handle;
    public static final class Preview {
        public final int width, height;
        public final double time;
        public final long source;
        public final byte[] rgba;
        private Preview(int width, int height, double time, long source, byte[] rgba) {
            this.width = width; this.height = height; this.time = time;
            this.source = source; this.rgba = rgba;
        }
    }
    private static void deliverPreview(CompletableFuture<Preview> future, Preview preview) {
        future.complete(preview);
    }
    public Player(Surface video, Surface subtitle, Display display, Map<String, String> options) {
        if (Build.VERSION.SDK_INT < 28) throw new UnsupportedOperationException("SenPlayer requires Android 9+");
        String[] names = options.keySet().toArray(new String[0]);
        String[] values = new String[names.length];
        for (int i = 0; i < names.length; i++) values[i] = options.get(names[i]);
        handle = N.n5(video, subtitle, new DeviceOutput(display), names, values, 0L);
        if (handle == 0L) throw new IllegalStateException("SenPlayer output initialization failed");
    }
    public synchronized long open(String url, String title, Map<String, String> options,
            double positionSeconds, boolean paused) {
        ArrayList<String> args = new ArrayList<>();
        args.add(url); args.add(title);
        for (Map.Entry<String, String> entry : options.entrySet()) {
            args.add(entry.getKey()); args.add(entry.getValue());
        }
        String value = command("open", 0L, args.toArray(new String[0]),
                new double[] {0.0, positionSeconds, paused ? 1.0 : 0.0});
        return value == null ? 0L : Long.parseLong(value);
    }
    private String command(String action, long source, String[] args, double[] numbers) {
        if (handle == 0L) throw new IllegalStateException("SenPlayer output is closed");
        byte[][] strings = new byte[args.length][];
        for (int i = 0; i < args.length; i++) strings[i] = args[i].getBytes(StandardCharsets.UTF_8);
        byte[] result = N.n3(handle, action, source, strings, numbers);
        return result == null ? null : new String(result, StandardCharsets.UTF_8);
    }
    public synchronized Playback$State state() { return handle == 0L ? null : N.n14(handle); }
    public synchronized Playback$Progress progress() { return handle == 0L ? null : N.n15(handle); }
    public synchronized String media() { return command("media", 0L, new String[0], new double[0]); }
    public synchronized int property(String name, String value) {
        if (handle == 0L) return -3;
        return N.n7(handle, name, value);
    }
    public synchronized int select(long source, int kind, long track, boolean off) {
        String result = command("select", source, new String[0], new double[] {kind, off ? 0.0 : 2.0, track});
        return result == null ? -3 : Integer.parseInt(result);
    }
    public synchronized int seek(double seconds) { return handle == 0L ? -3 : N.n10(handle, seconds); }
    public synchronized int paused(boolean paused) { return handle == 0L ? -3 : N.n9(handle, paused); }
    public synchronized int surface(Surface video, Surface subtitle) {
        return handle == 0L ? -3 : N.n6(handle, video, subtitle);
    }
    public synchronized CompletableFuture<Preview> preview(double seconds) {
        CompletableFuture<Preview> future = new CompletableFuture<>();
        if (handle == 0L) future.complete(null); else N.n16(handle, seconds, future);
        return future;
    }
    @Override public synchronized void close() {
        long value = handle;
        handle = 0L;
        if (value != 0L) N.n19(value);
    }
}
