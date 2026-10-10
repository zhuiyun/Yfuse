import com.yfuse.core2.android.FfmpegNativeBridge;
import com.yfuse.core2.android.AndroidYCoreGpuNativeBridge;
import java.nio.ByteBuffer;

public final class YCoreProbe {
    public static void main(String[] args) throws Exception {
        ProbeSupport.load(args[0]);
        int api = FfmpegNativeBridge.nativeSoftwareDecoderApiVersion();
        ProbeSupport.check(api >= 4, "Software decoder API is older than 4");
        long token = FfmpegNativeBridge.nativeCreateCancellation();
        long handle = 0;
        int packets = 0, frames = 0;
        try {
            handle = FfmpegNativeBridge.nativeOpenWithAnalysis(args[1], new String[0], new String[0], false, token);
            ProbeSupport.check(handle > 0, "Demux failed: " + FfmpegNativeBridge.nativeLastOpenFailure());
            int track = -1;
            for (int i = 0; i < FfmpegNativeBridge.nativeTrackCount(handle); i++) {
                if (FfmpegNativeBridge.nativeTrackType(handle, i) == 1) { track = i; break; }
            }
            ProbeSupport.check(track >= 0, "No video track");
            FfmpegNativeBridge.nativeSelectTracks(handle, new int[]{track});
            FfmpegNativeBridge.nativeConfigureSoftwareDecoder(handle, track, false);
            ByteBuffer packetBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
            ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(32 * 1024 * 1024);
            for (int i = 0; i < 100 && frames < 4; i++) {
                packetBuffer.clear();
                long[] packet = FfmpegNativeBridge.nativeReadPacket(handle, packetBuffer);
                if (packet == null || packet[2] <= 0) break;
                byte[] bytes = new byte[(int)packet[2]];
                packetBuffer.get(bytes);
                int status = FfmpegNativeBridge.nativeSendSoftwarePacket(handle, track, bytes, packet[3], packet[4]);
                ProbeSupport.check(status >= 0, "Packet send failed " + status);
                packets++;
                for (int frameIndex = 0; frameIndex < 10; frameIndex++) {
                    long[] frame = FfmpegNativeBridge.nativeReceiveSoftwareVideoFrame(handle, track, pixelBuffer);
                    if (frame == null || frame[1] <= 0) break;
                    ProbeSupport.check(frame[3] > 0 && frame[4] > 0, "Invalid decoded dimensions");
                    frames++;
                }
            }
            ProbeSupport.check(frames >= 4, "No decoded software frames: " + frames);
            System.out.println("PASS ycore independent software decode api=" + api + " packets=" + packets + " frames=" + frames);
            System.out.println("PASS ycore independent gpu load api=" + AndroidYCoreGpuNativeBridge.nativeGpuApiVersion() + " capabilityMask=" + AndroidYCoreGpuNativeBridge.nativeProbeGpuFeatures());
        } finally {
            if (handle > 0) FfmpegNativeBridge.nativeClose(handle);
            FfmpegNativeBridge.nativeReleaseCancellation(token);
        }
        System.out.println("PASS ycore independent release");
        System.exit(0);
    }
}
