package com.yfuse.senplayer;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A version-pinned adapter to the unmodified runtime supplied in SenPlayer.apk.
 * No decompiled class is recompiled and no native/permit/signature check is patched.
 * Construction is opt-in: it starts the original HTTPS license verifier with the real host Context.
 * Call all instance methods on the owning engine worker, never on the UI thread.
 */
public final class SenPlayerSession implements AutoCloseable {
  public static final String LICENSE_ENDPOINT = "https://www.senmuse.com/api/license";
  private static final String APK_SHA =
      "45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c";
  private static Runtime runtime;
  private final Runtime rt;
  private volatile Object player;
  private volatile long source;
  private volatile boolean usable = true;
  private final Object lifecycle = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();
  private CompletableFuture<?> pendingRelease;

  public static final class LicenseUnavailable extends Exception {
    public final String status;
    LicenseUnavailable(String status) {
      super("SenPlayer license unavailable: " + status);
      this.status = status.matches("[a-zA-Z0-9_]{1,80}") ? status : "unavailable";
    }
  }

  /** An optional picture enhancement was not accepted; this does not retire playback. */
  public static final class EnhancementOptionRejected extends IOException {
    public final String optionName;
    /** Original JNI result, or null when the original runtime returned no valid integer. */
    public final Integer nativeCode;

    EnhancementOptionRejected(String optionName, Integer nativeCode) {
      super("SenPlayer enhancement rejected: " + optionName + " ("
          + (nativeCode == null ? "unknown result" : nativeCode) + ")");
      this.optionName = optionName;
      this.nativeCode = nativeCode;
    }
  }

  public static final class Snapshot {
    public long source, positionMs, durationMs, cacheEndMs, videoFrames, surfaceFrames,
        surfaceDraws;
    public String phase, decoder, audioOutput, videoOutput;
    public int width, height, errorCode;
    public long cacheDurationMs;
    public boolean cacheSnapshotAvailable;
    /** Native execution/capability flags; distinct from an accepted option or saved preference. */
    public boolean sdrToHdrActive, upscalingActive, upscalingAvailable;
    public List<long[]> cacheRanges = new ArrayList<>();
  }

  public static boolean supportsProcess() {
    if (android.os.Build.VERSION.SDK_INT < 28 || !android.os.Process.is64Bit()) return false;
    // Native Bridge can label the running app arm64 while DexClassLoader still loads
    // extracted JNI libraries with the device's preferred 64-bit linker.
    String[] abis = android.os.Build.SUPPORTED_64_BIT_ABIS;
    return abis.length > 0 && "arm64-v8a".equals(abis[0]);
  }

  public SenPlayerSession(Context context, Surface surface, Surface subtitleSurface, Display display,
      Map<String, String> options) throws Exception {
    if (!supportsProcess())
      throw new UnsupportedOperationException("SenPlayer requires Android 9+ and arm64-v8a");
    rt = load(context.getApplicationContext());
    rt.acquire(context.getApplicationContext());
    try {
      rt.requirePermit();
      player = rt.playerClass
                   .getConstructor(Surface.class, Surface.class, Display.class, LinkedHashMap.class)
                   .newInstance(surface, subtitleSurface, display, new LinkedHashMap<>(options));
    } catch (Throwable error) {
      try {
        rt.release();
      } catch (Exception cleanup) {
        error.addSuppressed(cleanup);
      }
      throw unwrap(error);
    }
  }

  public void resize(int width, int height) throws Exception {
    invoke("B", new Class[] {int.class, int.class}, width, height);
  }
  public void surface(Surface surface, Surface subtitleSurface) throws Exception {
    checkCode(awaitOperation(
        invoke("x", new Class[] {Surface.class, Surface.class}, surface, subtitleSurface)));
  }

  public void open(String url, Map<String, String> headers, long positionMs, boolean play)
      throws Exception {
    ensureUsable();
    source = 0;
    rt.requirePermit();
    Class<?> requestClass = rt.loader.loadClass("ei3");
    Object request = requestClass.getConstructor(String.class).newInstance(url);
    requestClass.getField("d").setDouble(request, Math.max(0, positionMs) / 1000.0);
    requestClass.getField("e").setBoolean(request, !play);
    @SuppressWarnings("unchecked")
    Map<String, String> requestHeaders =
        (Map<String, String>) requestClass.getField("f").get(request);
    requestHeaders.putAll(headers);
    try {
      long opened =
          ((Number) awaitOperation(invoke("u", new Class[] {requestClass}, request))).longValue();
      synchronized (lifecycle) {
        ensureUsable();
        if (opened == 0)
          throw new IOException("SenPlayer did not open a media source");
        source = opened;
      }
      play(play);
    } catch (Exception error) {
      source = 0;
      pauseForFailure();
      throw error;
    }
  }

  public void play(boolean playing) throws Exception {
    if (playing)
      rt.requirePermit();
    checkCode(invoke("y", new Class[] {boolean.class}, !playing));
  }
  public void seek(long positionMs) throws Exception {
    command("seek", new String[0], Math.max(0, positionMs) / 1000.0, 1.0);
  }
  public void option(String name, String value) throws Exception {
    checkCode(awaitOperation(invoke("v", new Class[] {String.class, String.class}, name, value)));
  }
  /** Original SenPlayer SDR to HDR levels: 0 off, 1 low, 2 medium, 3 high. */
  public void setSdrToHdrLevel(int level) throws Exception {
    setEnhancementLevel("surface-sdr-hdr", level);
  }
  /** Original SenPlayer upscaling levels: 0 off, 1 low, 2 medium, 3 high. */
  public void setUpscalingLevel(int level) throws Exception {
    setEnhancementLevel("surface-upscaling", level);
  }
  private void setEnhancementLevel(String name, int level) throws Exception {
    if (level < 0 || level > 3)
      throw new IllegalArgumentException("SenPlayer enhancement level must be between 0 and 3");
    // Pinned DEX: op3.k0 / w8 -> Player.v -> N.n7. Preserve native rejection codes.
    Object result = awaitOperation(
        invoke("v", new Class[] {String.class, String.class}, name, Integer.toString(level)));
    Integer code = result instanceof Integer ? (Integer) result : null;
    if (code == null || code != 0)
      throw new EnhancementOptionRejected(name, code);
  }
  public void track(int kind, String id) throws Exception {
    // di3 AUDIO=0 SUBTITLE=1 SECONDARY=2; vh3 OFF=0 AUTO=1 TRACK=2, from the supplied DEX.
    double choice = "off".equals(id) ? 0 : "auto".equals(id) ? 1 : 2;
    command("select", new String[0], kind, choice, choice == 2 ? Long.parseLong(id) : 0);
  }

  /** Player.e("addSubtitle") returns the new track id as decimal text, unlike Player.G status codes. */
  public String addSubtitle(String localPath, String title) throws Exception {
    ensureUsable();
    rt.requirePermit();
    if (source == 0)
      throw new IllegalStateException("SenPlayer has no active source");
    Object raw = awaitOperation(invoke("e", new Class[] {String.class, long.class, String[].class, double[].class},
        "addSubtitle", source, new String[] {localPath, title}, new double[0]));
    return subtitleTrackId(raw);
  }

  static String subtitleTrackId(Object result) throws IOException {
    if (result instanceof String) {
      try {
        if (Long.parseLong((String) result) > 0) return (String) result;
      } catch (NumberFormatException ignored) {
        // Never treat a malformed native response as a selectable track id.
      }
    }
    throw new IOException("SenPlayer rejected external subtitle");
  }

  /** Original Player.M packs two gi3 styles and enables the subtitle output for this source. */
  public void configureSubtitles(boolean secondary, double primaryScale, double secondaryScale,
      double primaryDelay, double secondaryDelay, String foreground, String outline, String background,
      double outlineWidth, boolean customStyle) throws Exception {
    ensureUsable();
    rt.requirePermit();
    Class<?> configClass = rt.loader.loadClass("hi3");
    Object config = subtitleConfiguration(configClass, rt.loader.loadClass("gi3"), secondary,
        primaryScale, secondaryScale, primaryDelay, secondaryDelay, foreground, outline, background,
        outlineWidth, customStyle);
    checkCode(awaitOperation(invoke("M", new Class[] {configClass, long.class}, config, source)));
  }

  static Object subtitleConfiguration(Class<?> configClass, Class<?> styleClass, boolean secondary,
      double primaryScale, double secondaryScale, double primaryDelay, double secondaryDelay,
      String foreground, String outline, String background, double outlineWidth, boolean customStyle)
      throws ReflectiveOperationException {
    Object config = configClass.getConstructor().newInstance();
    for (boolean second : new boolean[] {false, true}) {
      Object style = styleClass.getConstructor().newInstance();
      styleClass.getField("a").set(style, "Roboto");
      styleClass.getField("b").set(style, foreground);
      styleClass.getField("c").set(style, outline);
      styleClass.getField("e").set(style, background);
      styleClass.getField("g").setDouble(style, second ? secondaryScale : primaryScale);
      styleClass.getField("i").setDouble(style, second ? secondaryDelay : primaryDelay);
      styleClass.getField("j").setDouble(style, outlineWidth);
      configClass.getField(second ? "b" : "a").set(config, style);
    }
    configClass.getField("c").set(config, "/system/fonts");
    configClass.getField("d").set(config, "none");
    configClass.getField("e").setBoolean(config, true); // visible
    configClass.getField("f").setBoolean(config, customStyle);
    configClass.getField("g").setBoolean(config, secondary);
    configClass.getField("i").setBoolean(config, true); // aspect correction
    configClass.getField("l").setDouble(config, 1.0); // HDR brightness
    configClass.getField("m").setDouble(config, 1.0); // opacity
    return config;
  }
  private void command(String command, String[] strings, double... numbers) throws Exception {
    rt.requirePermit();
    if (source == 0)
      throw new IllegalStateException("SenPlayer has no active source");
    checkCode(
        awaitOperation(invoke("G", new Class[] {String.class, long.class, String[].class, double[].class},
            command, source, strings, numbers)));
  }
  public JSONObject media() throws Exception {
    long expectedSource = source;
    Object raw =
        awaitOperation(invoke("e", new Class[] {String.class, long.class, String[].class, double[].class},
            "media", 0L, new String[0], new double[0]));
    JSONObject result = raw == null ? new JSONObject() : new JSONObject(raw.toString());
    // ai3's original media model has a root source id, independent of its track ids.
    return expectedSource != 0 && expectedSource == source
            && result.optLong("source") == expectedSource
        ? result : new JSONObject();
  }
  public Snapshot snapshot() throws Exception {
    rt.requirePermit();
    Object value = invoke("F", new Class[0]);
    Snapshot s = new Snapshot();
    s.source = number(value, "a").longValue();
    s.phase = text(value, "g");
    s.positionMs = milliseconds(number(value, "v").doubleValue());
    s.durationMs = milliseconds(number(value, "w").doubleValue());
    s.cacheDurationMs = milliseconds(number(value, "x").doubleValue());
    s.width = number(value, "n").intValue();
    s.height = number(value, "o").intValue();
    s.errorCode = number(value, "m").intValue();
    s.decoder = text(value, "j");
    s.audioOutput = text(value, "h");
    s.videoOutput = text(value, "i");
    s.videoFrames = number(value, "b").longValue();
    s.surfaceFrames = number(value, "d").longValue();
    s.surfaceDraws = number(value, "e").longValue();
    applyEnhancementState(s, value);
    if (s.source == 0 || s.source != source
        || !(s.phase.equals("playing") || s.phase.equals("paused") || s.phase.equals("buffering")
            || s.phase.equals("seeking")))
      return s;
    try {
      Object current = player;
      CompletableFuture<?> read;
      // Original mo3 schedules n15 on Player.o. Keep it ordered with open/seek/release.
      synchronized (current) {
        long handle = rt.playerClass.getField("g").getLong(current);
        if (handle == 0)
          return s;
        Executor executor = (Executor) rt.playerClass.getField("o").get(current);
        read = CompletableFuture.supplyAsync(() -> {
          try {
            return rt.nativeProgress.invoke(null, handle);
          } catch (Exception error) {
            throw new CompletionException(unwrap(error));
          }
        }, executor);
      }
      Object progress = awaitOperation(read);
      if (s.source == source)
        applyProgress(s, progress);
    } catch (Exception error) {
      if (!isUsable())
        throw error;
      // Original mo3 degrades an unavailable cache sample, without stopping playback.
      s.cacheSnapshotAvailable = false;
      s.cacheEndMs = 0;
      s.cacheRanges.clear();
    }
    return s;
  }
  public long sourceId() {
    return source;
  }
  public boolean isUsable() {
    return usable && !closed.get();
  }
  /** Pausing remains possible after a license expires; it never grants playback. */
  public void pauseForFailure() {
    Object current = player;
    if (current != null)
      try {
        rt.playerClass.getMethod("y", boolean.class).invoke(current, true);
      } catch (ReflectiveOperationException ignored) {
      }
  }
  public void cancelPendingRead() {
    // Called from another thread during release/failure. A late open must not resume playback.
    synchronized (lifecycle) {
      usable = false;
      source = 0;
    }
    Object current = player;
    if (current != null)
      try {
        rt.playerClass.getMethod("j").invoke(current);
      } catch (ReflectiveOperationException ignored) {
      }
    pauseForFailure();
  }
  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true))
      return;
    try {
      cancelPendingRead();
      CompletableFuture<?> release = beginNativeRelease();
      if (release != null)
        await(release);
    } finally {
      player = null;
      rt.release();
    }
  }
  private CompletableFuture<?> beginNativeRelease() throws Exception {
    synchronized (lifecycle) {
      if (pendingRelease == null && player != null)
        pendingRelease = (CompletableFuture<?>) rt.playerClass.getMethod("n").invoke(player);
      return pendingRelease;
    }
  }
  private void ensureUsable() {
    if (!isUsable())
      throw new IllegalStateException("SenPlayer session must be recreated");
  }
  private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
    synchronized (lifecycle) {
      ensureUsable();
      if (player == null)
        throw new IllegalStateException("SenPlayer session closed");
      try {
        return rt.playerClass.getMethod(name, types).invoke(player, args);
      } catch (InvocationTargetException e) {
        throw unwrap(e);
      }
    }
  }
  private static Object await(Object value) throws Exception {
    try {
      return ((CompletableFuture<?>) value).get(30, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      throw unwrap(error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw error;
    }
  }
  private Object awaitOperation(Object value) throws Exception {
    try {
      Object result = await(value);
      if (!isUsable())
        throw new CancellationException("SenPlayer operation retired");
      return result;
    } catch (TimeoutException | InterruptedException | CancellationException error) {
      cancelPendingRead();
      try {
        // CompletableFuture.cancel cannot prove that JNI stopped. Retire the original Player;
        // its n() detaches the handle and queues destruction behind the outstanding command.
        beginNativeRelease();
      } catch (Exception cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
  }
  private static void checkCode(Object value) throws IOException {
    if (!(value instanceof Number) || ((Number) value).intValue() != 0)
      throw new IOException("SenPlayer command failed (" + value + ")");
  }
  private static Number number(Object value, String field) throws Exception {
    return (Number) value.getClass().getField(field).get(value);
  }
  private static String text(Object value, String field) throws Exception {
    Object v = value.getClass().getField(field).get(value);
    return v == null ? "" : v.toString();
  }
  private static long milliseconds(double seconds) {
    return !Double.isFinite(seconds) || seconds < 0
        ? 0
        : (long) Math.min(Long.MAX_VALUE, seconds * 1000);
  }
  static long[] bufferedRange(double start, double end, long durationMs) {
    if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start
        || end > Long.MAX_VALUE / 1000.0)
      return null;
    long from = milliseconds(start);
    long to = milliseconds(end);
    if (durationMs > 0)
      to = Math.min(to, durationMs);
    return to > from ? new long[] {from, to} : null;
  }
  static double remainingPermitSeconds(boolean allowed, long deadline, long now) {
    return allowed && now >= 0 && deadline > now ? (deadline - now) / 1000.0 : 0;
  }
  static void applyEnhancementState(Snapshot snapshot, Object value) throws Exception {
    // Pinned DEX Playback$State -> yl1.A -> rp3: y=u SDR HDR active, A=v upscaling active,
    // z=w upscaling available. These are original native state, never inferred from preferences.
    snapshot.sdrToHdrActive = value.getClass().getField("y").getBoolean(value);
    snapshot.upscalingActive = value.getClass().getField("A").getBoolean(value);
    snapshot.upscalingAvailable = value.getClass().getField("z").getBoolean(value);
  }
  static void applyProgress(Snapshot s, Object progress) throws Exception {
    s.cacheSnapshotAvailable = false;
    s.cacheEndMs = 0;
    s.cacheRanges = new ArrayList<>();
    if (progress == null || s.source == 0 || number(progress, "a").longValue() != s.source)
      return;
    List<long[]> ranges = new ArrayList<>();
    for (Object range : (List<?>) progress.getClass().getField("f").get(progress)) {
      long[] valid = bufferedRange(number(range, "a").doubleValue(),
          number(range, "b").doubleValue(), s.durationMs);
      if (valid != null)
        ranges.add(valid);
    }
    s.cacheDurationMs = milliseconds(number(progress, "c").doubleValue());
    s.cacheEndMs = milliseconds(number(progress, "d").doubleValue());
    if (s.durationMs > 0) {
      s.cacheDurationMs = Math.min(s.cacheDurationMs, s.durationMs);
      s.cacheEndMs = Math.min(s.cacheEndMs, s.durationMs);
    }
    s.cacheRanges = ranges;
    s.cacheSnapshotAvailable = true;
  }
  private static Exception unwrap(Throwable error) {
    Throwable cause = error;
    while ((cause instanceof InvocationTargetException || cause instanceof ExecutionException
               || cause instanceof CompletionException) && cause.getCause() != null)
      cause = cause.getCause();
    return cause instanceof Exception
        ? (Exception) cause
        : new IllegalStateException("SenPlayer native linkage failed", cause);
  }

  private static synchronized Runtime load(Context context) throws Exception {
    if (runtime != null)
      return runtime;
    JSONObject manifest = new JSONObject(read(context.getAssets().open("senplayer/runtime.json")));
    if (!APK_SHA.equals(manifest.getString("sourceApkSha256")))
      throw new SecurityException("Unexpected SenPlayer source APK");
    File root = new File(context.getCodeCacheDir(), "senplayer-" + APK_SHA.substring(0, 16));
    if (!root.isDirectory() && !root.mkdirs())
      throw new IOException("Cannot create SenPlayer private runtime directory");
    JSONArray files = manifest.getJSONArray("files");
    for (int i = 0; i < files.length(); i++) {
      JSONObject entry = files.getJSONObject(i);
      String asset = entry.getString("asset");
      if (!asset.matches(
              "classes\\.dex|license\\.json|native/lib(FFmpegKit|MLibKit|c\\+\\+_shared)\\.so"))
        throw new SecurityException("Unexpected runtime entry");
      File output = new File(root, asset);
      File parent = output.getParentFile();
      if (!parent.isDirectory() && !parent.mkdirs())
        throw new IOException("Cannot create native directory");
      if (output.isFile() && entry.getString("sha256").equals(hash(output))) {
        if (!output.setReadOnly())
          throw new IOException("Runtime is writable");
        continue;
      }
      File pending = new File(parent, output.getName() + ".pending");
      if (pending.exists() && !pending.delete())
        throw new IOException("Cannot remove incomplete runtime file");
      try (InputStream in = context.getAssets().open("senplayer/" + asset);
          FileOutputStream out = new FileOutputStream(pending)) {
        // Android 14 requires dynamically loaded code to be read-only before writing via the open
        // descriptor.
        if (!pending.setReadOnly())
          throw new IOException("Cannot make runtime read-only");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.getFD().sync();
      }
      if (!entry.getString("sha256").equals(hash(pending)))
        throw new SecurityException("SenPlayer runtime checksum mismatch");
      if (output.exists() && !output.delete())
        throw new IOException("Cannot replace invalid runtime");
      if (!pending.renameTo(output))
        throw new IOException("Cannot publish verified runtime");
    }
    DexClassLoader loader =
        new DexClassLoader(new File(root, "classes.dex").getPath(), root.getPath(),
            // The original APK and Yfuse both use short obfuscated names (e.g. eb2/u54).
            // Delegate only to Android's boot/framework loader; a host-app parent would bind
            // the original runtime to unrelated Yfuse classes after release shrinking.
            new File(root, "native").getPath(), Context.class.getClassLoader());
    runtime = new Runtime(
        loader, new JSONObject(read(new FileInputStream(new File(root, "license.json")))));
    return runtime;
  }
  private static String read(InputStream in) throws IOException {
    try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = source.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    }
  }
  private static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    StringBuilder result = new StringBuilder();
    for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  private static final class Runtime {
    final DexClassLoader loader;
    final Class<?> playerClass, managerClass;
    final Method nativeProgress;
    final JSONObject licenseConfiguration;
    volatile Object license;
    int owners;
    Runtime(DexClassLoader loader, JSONObject config) throws Exception {
      this.loader = loader;
      licenseConfiguration = config;
      if (!LICENSE_ENDPOINT.equals(config.getString("endpoint"))
          || !"production".equals(config.getString("environment")))
        throw new SecurityException("Unexpected license endpoint");
      playerClass = Class.forName("com.mplaykit.Player", true, loader);
      managerClass = Class.forName("eb2", true, loader);
      Class<?> n = Class.forName("com.mplaykit.N", true, loader);
      nativeProgress = n.getDeclaredMethod("n15", long.class);
      nativeProgress.setAccessible(true);
    }
    void acquire(Context context) throws Exception {
      Object acquired;
      synchronized (this) {
        if (license == null) {
          final File storage = new File(context.getNoBackupFilesDir(), "senplayer-license");
          if (!storage.isDirectory() && !storage.mkdirs())
            throw new IOException("Cannot create license storage");
          Context scoped = new ContextWrapper(context) {
            @Override
            public File getNoBackupFilesDir() {
              return storage;
            }
            @Override
            public Context getApplicationContext() {
              return this;
            }
          };
          // Original manager, enforcement=true, original signed protocol and actual host
          // package/version.
          playerClass.getMethod("C", double.class).invoke(null, 0.0);
          Object created = managerClass.getConstructor(Context.class, boolean.class, JSONObject.class)
                               .newInstance(scoped, true,
                                   new JSONObject(licenseConfiguration.toString()));
          try {
            @SuppressWarnings("unchecked")
            List<Runnable> listeners = (List<Runnable>) managerClass.getField("t").get(created);
            // mi/n1 updates runtimePermit when the original manager publishes a new state.
            // Do not wait for the engine's polling worker, which may be blocked on native I/O.
            listeners.add(() -> refreshPermit(created));
            license = created;
          } catch (Exception error) {
            try {
              ((AutoCloseable) created).close();
            } catch (Exception cleanup) {
              error.addSuppressed(cleanup);
            }
            throw error;
          }
        }
        owners++;
        acquired = license;
      }
      try {
        long until = SystemClock.elapsedRealtime() + 20000;
        while (!(Boolean) managerClass.getMethod("a").invoke(acquired)
            && SystemClock.elapsedRealtime() < until) {
          Thread.sleep(200);
        }
        requirePermit();
      } catch (Exception error) {
        if (error instanceof InterruptedException)
          Thread.currentThread().interrupt();
        try {
          release();
        } catch (Exception cleanup) {
          error.addSuppressed(cleanup);
        }
        throw error;
      }
    }
    private synchronized void refreshPermit(Object expected) {
      if (license != expected)
        return;
      try {
        requirePermit();
      } catch (Exception error) {
        // A denied state already sent zero; reflection failures must also fail closed.
        try {
          playerClass.getMethod("C", double.class).invoke(null, 0.0);
        } catch (ReflectiveOperationException ignored) {
        }
      }
    }
    synchronized void requirePermit() throws Exception {
      if (license == null)
        throw new LicenseUnavailable("not_initialized");
      boolean allowed = (Boolean) managerClass.getMethod("a").invoke(license);
      JSONObject state = (JSONObject) managerClass.getField("u").get(license);
      long now = SystemClock.elapsedRealtime();
      long deadline = state.optLong("deadlineMono");
      double remaining = remainingPermitSeconds(allowed && state.optBoolean("allowed"), deadline, now);
      // Exactly mi/n1 behavior: never invent a duration or extend a grant after its deadline.
      playerClass.getMethod("C", double.class).invoke(null, remaining);
      if (!allowed || remaining <= 0)
        throw new LicenseUnavailable(state.optString("status", "unavailable"));
    }
    synchronized void release() throws Exception {
      owners = Math.max(0, owners - 1);
      if (owners == 0 && license != null) {
        Object previous = license;
        license = null;
        try {
          ((AutoCloseable) previous).close();
        } finally {
          playerClass.getMethod("C", double.class).invoke(null, 0.0);
        }
      }
    }
  }
}
