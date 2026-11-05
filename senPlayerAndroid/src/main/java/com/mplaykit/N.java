package com.mplaykit;

import android.view.Surface;
import java.util.concurrent.CompletableFuture;

/** JNI declarations matching the supplied Android build 8 binary. */
final class N {
    public static final /* synthetic */ int a = 0;

    static {
        System.loadLibrary("FFmpegKit");
    }

    public static native String n0();

    public static native double n1(String str);

    public static native int n10(long j, double d);

    public static native int n11(long j);

    public static native void n12(long j, int i, int i2);

    public static native String n13(long j);

    public static native Playback$State n14(long j);

    public static native Playback$Progress n15(long j);

    public static native void n16(long j, double d, CompletableFuture<Player.Preview> completableFuture);

    public static native void n17(long j);

    public static native int n18(long j, long j2, long j3);

    public static native void n19(long j);

    public static native int n2(Runnable runnable);

    public static native double n20(double d, double d2);

    public static native long n21(byte[][] bArr, int i, double d, double d2, boolean z);

    public static native long n22(long j);

    public static native PreviewReader$Still n23(long j, long j2, double d);

    public static native byte[] n24(long j, long j2);

    public static native void n25(long j);

    public static native void n26(long j);

    public static native long n27();

    public static native void n28();

    public static native void n29(int i, byte[] bArr, byte[] bArr2);

    public static native byte[] n3(long j, String str, long j2, byte[][] bArr, double[] dArr);

    public static native long n30(long j);

    public static native void n31(long j);

    public static native long n4(long j);

    public static native long n5(Surface surface, Surface surface2, Object obj, String[] strArr, String[] strArr2, long j);

    public static native int n6(long j, Surface surface, Surface surface2);

    public static native int n7(long j, String str, String str2);

    public static native long n8(long j, String str);

    public static native int n9(long j, boolean z);
}
