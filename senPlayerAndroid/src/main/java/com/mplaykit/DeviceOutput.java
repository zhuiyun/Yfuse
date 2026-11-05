package com.mplaykit;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.view.Display;
import java.util.ArrayList;

/** Callback method names are part of the native object's ABI. */
public final class DeviceOutput {
    private final Display display;
    public DeviceOutput(Display display) { this.display = display; }
    public int[] a() {
        return display == null || !display.isValid() ? new int[0] : display.getHdrCapabilities().getSupportedHdrTypes();
    }
    public Object[] b(String mime) {
        ArrayList<Object> result = new ArrayList<>();
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder()) continue;
            for (String type : codec.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(mime)) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(type);
                    int[] profile = new int[2 + 2 * caps.profileLevels.length];
                    profile[0] = caps.isFeatureRequired("secure-playback") ? 1 : 0;
                    profile[1] = caps.isFeatureRequired("tunneled-playback") ? 1 : 0;
                    for (int i = 0; i < caps.profileLevels.length; i++) {
                        profile[2 + 2 * i] = caps.profileLevels[i].profile;
                        profile[3 + 2 * i] = caps.profileLevels[i].level;
                    }
                    result.add(codec); result.add(profile);
                } catch (IllegalArgumentException | IllegalStateException ignored) { }
            }
        }
        return result.toArray();
    }
    public boolean c(Object codec, String mime, int profile, int level, int width, int height, double fps) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger(MediaFormat.KEY_PROFILE, profile);
            format.setInteger(MediaFormat.KEY_LEVEL, level);
            if (fps > 0 && Double.isFinite(fps)) format.setFloat(MediaFormat.KEY_FRAME_RATE, (float) fps);
            return ((MediaCodecInfo) codec).getCapabilitiesForType(mime).isFormatSupported(format);
        } catch (IllegalArgumentException | IllegalStateException error) { return false; }
    }
    public String d(Object codec) { return ((MediaCodecInfo) codec).getName(); }
}
