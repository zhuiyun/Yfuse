import android.content.Context;
import android.content.ContextWrapper;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

final class ProbeSupport {
    static Context context(String folder) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context original = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        original.getApplicationInfo().dataDir = folder;
        try {
            original.getApplicationInfo().getClass().getField("credentialProtectedDataDir")
                .set(original.getApplicationInfo(), folder);
        } catch (NoSuchFieldException ignored) { }
        original.getApplicationInfo().deviceProtectedDataDir = folder;
        Object application = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        if (application instanceof ContextWrapper) {
            Object base = ((ContextWrapper)application).getBaseContext();
            for (String fieldName : new String[]{"mCacheDir", "mFilesDir", "mCredentialProtectedBaseDir", "mDeviceProtectedBaseDir"}) {
                try {
                    java.lang.reflect.Field field = base.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true); field.set(base, new File(folder));
                } catch (NoSuchFieldException ignored) { }
            }
        }
        return new ContextWrapper(original) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return new File(folder); }
            @Override public File getFilesDir() { return new File(folder); }
            // app_process is not registered as an ActivityManager application. The offline probe
            // needs no connectivity/headset broadcasts; playback and codec calls remain real.
            @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) { return null; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String permission, Handler scheduler) { return null; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String permission, Handler scheduler, int flags) { return null; }
            @Override public void unregisterReceiver(BroadcastReceiver receiver) { }
        };
    }
    static void load(String folder) throws Exception {
        for (String name : Files.readAllLines(Paths.get(folder, "load-order.txt"))) {
            System.load(folder + "/" + name);
            System.out.println("LOADED " + name);
        }
    }
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    static final class Output implements AutoCloseable {
        final AtomicInteger frames = new AtomicInteger();
        final HandlerThread thread = new HandlerThread("independent-frame-drain");
        final ImageReader reader;
        Output() { this(PixelFormat.RGBX_8888); }
        Output(int format) {
            reader = ImageReader.newInstance(320, 180, format, 3);
            thread.start();
            reader.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireLatestImage()) {
                    if (image != null) frames.incrementAndGet();
                }
            }, new Handler(thread.getLooper()));
        }
        public void close() { reader.close(); thread.quitSafely(); }
    }
    static final class TextureOutput implements AutoCloseable {
        final AtomicInteger frames = new AtomicInteger();
        final HandlerThread thread = new HandlerThread("independent-texture-events");
        final SurfaceTexture texture = new SurfaceTexture(false);
        final Surface surface;
        TextureOutput() {
            thread.start(); texture.setDefaultBufferSize(320, 180);
            texture.setOnFrameAvailableListener(t -> frames.incrementAndGet(), new Handler(thread.getLooper()));
            surface = new Surface(texture);
        }
        public void close() { surface.release(); texture.release(); thread.quitSafely(); }
    }
}
