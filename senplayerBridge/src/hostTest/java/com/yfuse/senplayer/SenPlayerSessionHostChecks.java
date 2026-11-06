package com.yfuse.senplayer;

import android.view.Surface;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import sun.misc.Unsafe;

/**
 * Standalone JVM boundary checks: no Android methods, original DEX, JNI, or network run here.
 * Test-only allocation injects reflection-compatible doubles without constructing the license
 * runtime. It must never be used as evidence that native playback or licensing passed.
 */
public final class SenPlayerSessionHostChecks {
  private static final Unsafe ALLOCATOR;
  private static int checks;
  static {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      ALLOCATOR = (Unsafe) field.get(null);
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  public static void main(String[] args) throws Exception {
    rangesNeverInventCoverage();
    progressUsesItsOwnSourceAndPreservesGaps();
    surfaceFailureIsNotSuccess();
    enhancementOptionsUseOriginalKeysAndLevels();
    enhancementRejectionsPreserveNativeResult();
    enhancementLevelsAreValidatedBeforeDispatch();
    enhancementStateUsesNativeExecutionFlags();
    pauseDoesNotRequireALicense();
    timeoutRetiresInsteadOfReusingThePlayer();
    cancellationRejectsLateCompletion();
    interruptionIsPreserved();
    ordinaryOperationFailureKeepsItsCause();
    closeReleasesOneOwnerEvenOnNativeFailure();
    sharedLicenseClosesOnlyAfterItsLastOwner();
    permitDeadlinesDoNotWrap();
    staleLicenseCallbackCannotChangeNewOwner();
    System.out.println("SEN_BRIDGE_HOST_CHECKS_PASSED assertions=" + checks);
    System.out.println("NO_ORIGINAL_RUNTIME_NO_JNI_NO_NETWORK");
  }

  private static void rangesNeverInventCoverage() throws Exception {
    for (double[] range : new double[][] {{Double.NaN, 10}, {-1, 10},
             {0, Double.NaN}, {0, Double.POSITIVE_INFINITY}, {10, 9}, {5, 5},
             {0, Double.MAX_VALUE}, {10, 10.0000001}}) {
      check(SenPlayerSession.bufferedRange(range[0], range[1], 0) == null,
          "invalid/empty range must not become [0,end]");
    }
    long[] clipped = SenPlayerSession.bufferedRange(8, 15, 10000);
    check(clipped[0] == 8000 && clipped[1] == 10000, "clip only known duration");
    check(SenPlayerSession.bufferedRange(12, 15, 10000) == null, "past-duration range");
    long[] unknownDuration = SenPlayerSession.bufferedRange(20, 30, 0);
    check(unknownDuration[0] == 20000 && unknownDuration[1] == 30000,
        "unknown duration does not erase real coverage");
    Method convert = SenPlayerSession.class.getDeclaredMethod("milliseconds", double.class);
    convert.setAccessible(true);
    check((long) convert.invoke(null, Double.MAX_VALUE) == Long.MAX_VALUE,
        "scalar overflow saturates without wrapping negative");
    check((long) convert.invoke(null, Double.NaN) == 0, "unknown scalar is zero");
  }

  private static void progressUsesItsOwnSourceAndPreservesGaps() throws Exception {
    SenPlayerSession.Snapshot sample = new SenPlayerSession.Snapshot();
    sample.source = 7;
    sample.positionMs = 10000;
    sample.durationMs = 100000;
    Progress progress = new Progress(7, 5, 50,
        new Range(10, 15), new Range(40, 50), new Range(Double.NaN, 90));
    SenPlayerSession.applyProgress(sample, progress);
    check(sample.cacheSnapshotAvailable, "same-source progress available");
    check(sample.cacheDurationMs == 5000 && sample.cacheEndMs == 50000,
        "duration is not the distance to the last sparse endpoint");
    check(sample.cacheRanges.size() == 2, "drop malformed range only");
    check(sample.cacheRanges.get(0)[1] == 15000 && sample.cacheRanges.get(1)[0] == 40000,
        "do not fill holes between ranges");
    SenPlayerSession.applyProgress(sample, new Progress(8, 99, 99, new Range(0, 99)));
    check(!sample.cacheSnapshotAvailable && sample.cacheRanges.isEmpty() && sample.cacheEndMs == 0,
        "late progress from a different source cannot replace current coverage");
    SenPlayerSession.applyProgress(sample, null);
    check(!sample.cacheSnapshotAvailable, "null progress is unknown");
    SenPlayerSession.applyProgress(sample, new Progress(7, 0, 0));
    check(sample.cacheSnapshotAvailable && sample.cacheRanges.isEmpty(),
        "a successful empty sample is distinct from an unavailable sample");
    sample.durationMs = 5000;
    SenPlayerSession.applyProgress(sample, new Progress(7, 99, 99, new Range(0, 99)));
    check(sample.cacheDurationMs == 5000 && sample.cacheEndMs == 5000,
        "native metrics cannot overrun known media duration");
  }

  private static void surfaceFailureIsNotSuccess() throws Exception {
    Fixture f = new Fixture(1);
    f.player.surfaceCode = -3;
    expect(IOException.class, () -> f.session.surface(null, null));
    f.player.surfaceCode = 0;
    f.session.surface(null, null);
    check(f.session.isUsable(), "successful surface detach leaves session reusable");
    f.session.close();
  }

  private static void pauseDoesNotRequireALicense() throws Exception {
    Fixture f = new Fixture(1);
    // The fake runtime has no license-evaluation methods. Calling requirePermit would fail.
    f.session.play(false);
    check(f.player.paused, "pause remains possible independently of permission renewal");
    f.session.close();
  }

  private static void enhancementOptionsUseOriginalKeysAndLevels() throws Exception {
    Fixture f = new Fixture(1);
    for (int level = 0; level <= 3; level++) {
      f.session.setSdrToHdrLevel(level);
      check(f.player.optionName.equals("surface-sdr-hdr")
              && f.player.optionValue.equals(Integer.toString(level)),
          "HDR level maps unchanged to original option");
      f.session.setUpscalingLevel(level);
      check(f.player.optionName.equals("surface-upscaling")
              && f.player.optionValue.equals(Integer.toString(level)),
          "upscaling level maps unchanged to original option");
    }
    check(f.player.optionCalls == 8, "each level dispatches once");
    check(FakePlayer.permitResets == 0, "picture settings never alter the license grant");
    f.session.close();
  }

  private static void enhancementRejectionsPreserveNativeResult() throws Exception {
    Fixture f = new Fixture(1);
    for (Object result : new Object[] {-3, 1, null, "0", 0.0d, 0L}) {
      f.player.optionResult = result;
      for (boolean hdr : new boolean[] {true, false}) {
        try {
          if (hdr) f.session.setSdrToHdrLevel(2);
          else f.session.setUpscalingLevel(2);
          throw new AssertionError("Rejected/malformed enhancement result was accepted");
        } catch (SenPlayerSession.EnhancementOptionRejected error) {
          check(error.optionName.equals(hdr ? "surface-sdr-hdr" : "surface-upscaling"),
              "rejection identifies the original option");
          check(java.util.Objects.equals(error.nativeCode, result instanceof Integer ? result : null),
              "preserve integer code; malformed or absent result stays unknown");
          check(f.session.isUsable(), "optional enhancement rejection must not retire playback");
        }
      }
    }
    f.player.optionResult = 0;
    f.session.setSdrToHdrLevel(0);
    f.session.setUpscalingLevel(0);
    check(f.session.isUsable(), "effects can be disabled after a rejection");
    f.session.close();
  }

  private static void enhancementLevelsAreValidatedBeforeDispatch() throws Exception {
    Fixture f = new Fixture(1);
    for (int level : new int[] {-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      expect(IllegalArgumentException.class, () -> f.session.setSdrToHdrLevel(level));
      expect(IllegalArgumentException.class, () -> f.session.setUpscalingLevel(level));
    }
    check(f.player.optionCalls == 0, "invalid levels cannot reach the original runtime");
    check(f.session.isUsable(), "invalid input leaves playback usable");
    f.session.close();
  }

  private static void enhancementStateUsesNativeExecutionFlags() throws Exception {
    SenPlayerSession.Snapshot snapshot = new SenPlayerSession.Snapshot();
    for (int mask = 0; mask < 8; mask++) {
      PictureState state = new PictureState((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
      SenPlayerSession.applyEnhancementState(snapshot, state);
      check(snapshot.sdrToHdrActive == state.y, "HDR active reads original field y");
      check(snapshot.upscalingActive == state.A, "upscaling active reads original field A");
      check(snapshot.upscalingAvailable == state.z,
          "upscaling availability reads original field z independently of active state");
    }
  }

  private static void timeoutRetiresInsteadOfReusingThePlayer() throws Exception {
    Fixture f = new Fixture(1);
    CompletableFuture<Object> late = new CompletableFuture<>() {
      @Override public Object get(long timeout, TimeUnit unit) throws TimeoutException {
        throw new TimeoutException("deliberately pending native command");
      }
    };
    expect(TimeoutException.class, () -> await(f.session, late));
    check(!f.session.isUsable() && f.session.sourceId() == 0,
        "timeout invalidates current source and player");
    check(f.player.cancelCalls > 0 && f.player.paused && f.player.releaseCalls == 1,
        "native read interrupted, paused and queued for destruction");
    late.complete(123L);
    check(f.session.sourceId() == 0 && !f.session.isUsable(), "late open cannot revive source");
    expect(IllegalStateException.class, () -> f.session.resize(320, 180));
    f.session.close();
    f.session.close();
    check(f.player.releaseCalls == 1 && f.license.closeCalls == 1,
        "retired player and license owner are released exactly once");
  }

  private static void cancellationRejectsLateCompletion() throws Exception {
    Fixture f = new Fixture(1);
    f.session.cancelPendingRead();
    expect(CancellationException.class,
        () -> await(f.session, CompletableFuture.completedFuture(888L)));
    check(f.session.sourceId() == 0 && !f.session.isUsable(), "cancel beats a late completion");
    f.session.close();
  }

  private static void interruptionIsPreserved() throws Exception {
    Fixture f = new Fixture(1);
    CompletableFuture<Object> interrupted = new CompletableFuture<>() {
      @Override public Object get(long timeout, TimeUnit unit) throws InterruptedException {
        throw new InterruptedException("test worker interrupted");
      }
    };
    try {
      expect(InterruptedException.class, () -> await(f.session, interrupted));
      check(Thread.currentThread().isInterrupted(), "restore thread interruption");
      check(!f.session.isUsable(), "interrupted JNI wait cannot be reused");
    } finally {
      Thread.interrupted();
      f.session.close();
    }
  }

  private static void ordinaryOperationFailureKeepsItsCause() throws Exception {
    Fixture f = new Fixture(1);
    CompletableFuture<Object> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IOException("cache query failed"));
    expect(IOException.class, () -> await(f.session, failed));
    check(f.session.isUsable(), "ordinary cache failure can degrade without retiring playback");
    f.session.close();
  }

  private static void closeReleasesOneOwnerEvenOnNativeFailure() throws Exception {
    Fixture f = new Fixture(1);
    f.player.releaseFailure = true;
    expect(IOException.class, f.session::close);
    f.session.close();
    check(f.license.closeCalls == 1 && FakePlayer.permitResets == 1,
        "failed native teardown still releases one license owner");
    check(!f.session.isUsable() && f.session.sourceId() == 0, "close detaches failed session");
  }

  private static void sharedLicenseClosesOnlyAfterItsLastOwner() throws Exception {
    Fixture f = new Fixture(2);
    SenPlayerSession other = session(f.runtime, new FakePlayer());
    f.session.close();
    check(f.license.closeCalls == 0 && FakePlayer.permitResets == 0,
        "one session cannot revoke another owner's license");
    other.close();
    other.close();
    check(f.license.closeCalls == 1 && FakePlayer.permitResets == 1,
        "last owner resets permit and closes original manager once");
  }

  private static void permitDeadlinesDoNotWrap() {
    check(SenPlayerSession.remainingPermitSeconds(true, 6000, 1000) == 5,
        "remaining permit uses deadline minus current monotonic time");
    check(SenPlayerSession.remainingPermitSeconds(false, 6000, 1000) == 0,
        "denied state cannot grant time");
    check(SenPlayerSession.remainingPermitSeconds(true, 1000, 1000) == 0,
        "deadline reached");
    check(SenPlayerSession.remainingPermitSeconds(true, Long.MIN_VALUE, 1000) == 0,
        "expired/invalid deadline cannot overflow to a positive grant");
    check(SenPlayerSession.remainingPermitSeconds(true, Long.MAX_VALUE, Long.MAX_VALUE - 500) == .5,
        "large clocks preserve a sub-second remaining interval");
  }

  private static void staleLicenseCallbackCannotChangeNewOwner() throws Exception {
    Fixture f = new Fixture(1);
    Method callback = f.runtime.getClass().getDeclaredMethod("refreshPermit", Object.class);
    callback.setAccessible(true);
    callback.invoke(f.runtime, new FakeLicense());
    check(FakePlayer.permitResets == 0, "retired manager callback cannot revoke new owner");
    // Missing evaluation methods deliberately exercise fail-closed callback handling.
    callback.invoke(f.runtime, f.license);
    check(FakePlayer.permitResets == 1, "callback evaluation failure resets permit");
    f.session.close();
  }

  private static Object await(SenPlayerSession session, Object future) throws Exception {
    Method method = SenPlayerSession.class.getDeclaredMethod("awaitOperation", Object.class);
    method.setAccessible(true);
    try {
      return method.invoke(session, future);
    } catch (InvocationTargetException error) {
      if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
      throw error;
    }
  }
  private static SenPlayerSession session(Object runtime, FakePlayer player) throws Exception {
    SenPlayerSession result = (SenPlayerSession) ALLOCATOR.allocateInstance(SenPlayerSession.class);
    set(result, "rt", runtime);
    set(result, "player", player);
    set(result, "source", 42L);
    set(result, "usable", true);
    set(result, "lifecycle", new Object());
    set(result, "closed", new AtomicBoolean());
    return result;
  }
  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
  private static void check(boolean condition, String reason) {
    checks++;
    if (!condition) throw new AssertionError(reason);
  }
  private interface Operation { void run() throws Exception; }
  private static void expect(Class<? extends Exception> type, Operation operation) throws Exception {
    try {
      operation.run();
    } catch (Exception error) {
      check(type.isInstance(error), "expected " + type.getSimpleName() + ", got " + error);
      return;
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  private static final class Fixture {
    final FakePlayer player = new FakePlayer();
    final FakeLicense license = new FakeLicense();
    final Object runtime;
    final SenPlayerSession session;
    Fixture(int owners) throws Exception {
      FakePlayer.permitResets = 0;
      runtime = ALLOCATOR.allocateInstance(Class.forName(SenPlayerSession.class.getName() + "$Runtime"));
      set(runtime, "playerClass", FakePlayer.class);
      set(runtime, "owners", owners);
      set(runtime, "license", license);
      session = session(runtime, player);
    }
  }
  public static final class FakeLicense implements AutoCloseable {
    int closeCalls;
    @Override public void close() { closeCalls++; }
  }
  public static final class FakePlayer {
    static int permitResets;
    int cancelCalls, releaseCalls, surfaceCode;
    boolean paused, releaseFailure;
    int optionCalls;
    Object optionResult = 0;
    String optionName, optionValue;
    public static void C(double permit) {
      if (permit != 0) throw new AssertionError("No grants may be issued by this host test");
      permitResets++;
    }
    public int y(boolean pause) { paused = pause; return 0; }
    public void j() { cancelCalls++; }
    public void B(int width, int height) { }
    public CompletableFuture<Integer> x(Surface video, Surface subtitles) {
      return CompletableFuture.completedFuture(surfaceCode);
    }
    public CompletableFuture<Object> v(String name, String value) {
      optionCalls++;
      optionName = name;
      optionValue = value;
      return CompletableFuture.completedFuture(optionResult);
    }
    public CompletableFuture<Void> n() {
      releaseCalls++;
      CompletableFuture<Void> result = new CompletableFuture<>();
      if (releaseFailure) result.completeExceptionally(new IOException("native release failed"));
      else result.complete(null);
      return result;
    }
  }
  public static final class Progress {
    public final long a;
    public final double c, d;
    public final List<Range> f = new ArrayList<>();
    Progress(long source, double duration, double end, Range... ranges) {
      a = source; c = duration; d = end;
      java.util.Collections.addAll(f, ranges);
    }
  }
  public static final class PictureState {
    public final boolean y, A, z;
    PictureState(boolean hdrActive, boolean upscaleActive, boolean upscaleAvailable) {
      y = hdrActive; A = upscaleActive; z = upscaleAvailable;
    }
  }
  public static final class Range {
    public final double a, b;
    Range(double start, double end) { a = start; b = end; }
  }
}
