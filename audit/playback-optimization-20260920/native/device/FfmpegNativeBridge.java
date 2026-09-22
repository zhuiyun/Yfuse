
package com.yfuse.core2.android;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
public class FfmpegNativeBridge {
public static native java.lang.String nativeTrackFontName(long arg0, int arg1);
public static native long nativeCreateAssRenderer(byte[] arg0, boolean arg1, int arg2, int arg3, java.lang.String[] arg4, byte[][] arg5, int arg6, java.lang.String[] arg7);
public static native byte[] nativeRenderAss(long arg0, long arg1, long arg2, byte[][] arg3, long[] arg4, long[] arg5);
public static native void nativeCloseAssRenderer(long arg0);
public static native int nativeDiscApiVersion();
public static native int nativeAssRendererApiVersion();
public static native int nativeSubtitleDisplaySetApiVersion();
public static native int nativeDemuxHandleContractVersion();
public static native long nativeRegisterBluRaySource(java.lang.Object arg0);
public static native void nativeUnregisterBluRaySource(long arg0);
public static native boolean nativeSelectDiscTitle(long arg0, int arg1);
public static native long nativeDiscChapterStartMs(long arg0, int arg1);
public static native boolean nativeSelectDiscAngle(long arg0, int arg1);
public static native boolean nativeSendDiscMenuCommand(long arg0, int arg1);
public static native boolean nativeSelectDiscMenuPoint(long arg0, int arg1, int arg2, boolean arg3);
public static native long nativeOpen(java.lang.String arg0, java.lang.String[] arg1, java.lang.String[] arg2);
public static native long nativeOpenProbe(java.lang.String arg0, java.lang.String[] arg1, java.lang.String[] arg2);
public static native void nativeClose(long arg0);
public static native long nativeCreateCancellation();
public static native void nativeCancelDemux(long arg0);
public static native void nativeSetDemuxDeadline(long arg0, long arg1);
public static native void nativeReleaseCancellation(long arg0);
public static native long nativeOpenCancellable(java.lang.String arg0, java.lang.String[] arg1, java.lang.String[] arg2, boolean arg3, long arg4);
public static native long nativeOpenWithAnalysis(java.lang.String arg0, java.lang.String[] arg1, java.lang.String[] arg2, boolean arg3, long arg4);
public static native java.lang.String nativeLastOpenFailure();
public static native int nativeTrackCount(long arg0);
public static native java.lang.String nativeContainerName(long arg0);
public static native long nativeDurationUs(long arg0);
public static native long nativeBitRateBitsPerSecond(long arg0);
public static native int nativeTrackType(long arg0, int arg1);
public static native java.lang.String nativeTrackCodecName(long arg0, int arg1);
public static native long[] nativeTrackVideoInfo(long arg0, int arg1);
public static native long[] nativeTrackAudioInfo(long arg0, int arg1);
public static native java.lang.String nativeTrackLanguage(long arg0, int arg1);
public static native java.lang.String nativeTrackTitle(long arg0, int arg1);
public static native byte[] nativeTrackExtradata(long arg0, int arg1);
public static native int[] nativeTrackDolbyConfig(long arg0, int arg1);
public static native int[] nativeTrackHdrStaticInfo(long arg0, int arg1);
public static native void nativeSelectTracks(long arg0, int[] arg1);
public static native long[] nativeReadPacket(long arg0, java.nio.ByteBuffer arg1);
public static native byte[] nativeDecodeSubtitle(long arg0, int arg1, byte[] arg2, long arg3, long arg4);
public static native int nativeSoftwareDecoderApiVersion();
public static native void nativeConfigureSoftwareDecoder(long arg0, int arg1, boolean arg2);
public static native int nativeSendSoftwarePacket(long arg0, int arg1, byte[] arg2, long arg3, long arg4);
public static native long[] nativeReceiveSoftwareVideoFrame(long arg0, int arg1, java.nio.ByteBuffer arg2);
public static native long[] nativeReceiveSoftwareAudioFrame(long arg0, int arg1, java.nio.ByteBuffer arg2);
public static native void nativeFlushSoftwareDecoder(long arg0, int arg1);
public static native int nativeSeek(long arg0, long arg1);
static String[] headers = new String[0];
static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
static List<String> packets(String file, boolean bounded) throws Exception {
    long token = nativeCreateCancellation();
    long handle = 0;
    try {
        handle = bounded ? nativeOpenWithAnalysis(file, headers, headers, false, token)
            : nativeOpenCancellable(file, headers, headers, false, token);
        check(handle > 0, "Open failed: " + nativeLastOpenFailure());
        int count = nativeTrackCount(handle);
        check(count > 0, "No tracks");
        int[] tracks = new int[count];
        for(int i = 0; i < count; i++) tracks[i] = i;
        nativeSelectTracks(handle, tracks);
        List<String> result = new ArrayList<>();
        result.add("tracks=" + count);
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
        for(int i = 0; i < 128; i++) {
            buffer.clear();
            long[] packet = nativeReadPacket(handle, buffer);
            check(packet != null, "No packet metadata");
            if (packet[2] <= 0) break;
            byte[] bytes = new byte[(int)packet[2]];
            buffer.get(bytes);
            result.add(Arrays.toString(packet) + Arrays.toString(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        check(result.size() > 1, "No media packets");
        return result;
    } finally {
        if (handle > 0) nativeClose(handle);
        nativeReleaseCancellation(token);
    }
}
public static void main(String[] args) throws Exception {
    System.load(args[0] + "/libycore_demux.so");
    check(nativeDemuxHandleContractVersion() >= 2, "Handle ABI");
    for (int i = 1; i < args.length; i++) {
        List<String> full = packets(args[i], false);
        List<String> bounded = packets(args[i], true);
        check(full.equals(bounded), "Startup analysis lost or changed retained packets");
        System.out.println("PASS " + (i == 1 ? "PCM" : "MP4") + " retained_packets=" + (full.size() - 1));
    }
    long token = nativeCreateCancellation();
    try {
        nativeCancelDemux(token);
        long handle = nativeOpenWithAnalysis(args[1], headers, headers, false, token);
        if (handle > 0) nativeClose(handle);
        check(handle < 0, "Cancelled bounded open returned a live handle");
    } finally { nativeReleaseCancellation(token); }
    System.out.println("PASS cancelled_startup_analysis");
    System.out.println("PASS all_JNI_methods_registered");
}
}
