import android.content.Context;
import dev.jdtech.mpv.MPVLib;

public final class MpvProbe {
    public static void main(String[] args) throws Exception {
        Context context = ProbeSupport.context(args[0]);
        ProbeSupport.load(args[0]);
        MPVLib player = MPVLib.create(context);
        ProbeSupport.check(player != null, "MPV instance creation failed");
        try (ProbeSupport.Output output = new ProbeSupport.Output()) {
            try {
                player.setOptionString("vo", "gpu");
                player.setOptionString("gpu-context", "android");
                player.setOptionString("hwdec", "no");
                player.setOptionString("ao", "null");
                player.setOptionString("keep-open", "yes");
                player.init();
                player.attachSurface(output.reader.getSurface());
                player.command(new String[]{"loadfile", args[1], "replace"});
                long deadline = System.currentTimeMillis() + 20000;
                Double position = null;
                while (System.currentTimeMillis() < deadline) {
                    position = player.getPropertyDouble("time-pos");
                    if (position != null && position > 0.3 && output.frames.get() > 0) break;
                    Thread.sleep(50);
                }
                ProbeSupport.check(position != null && position > 0.3, "MPV playback did not advance");
                ProbeSupport.check(output.frames.get() > 0, "MPV produced no rendered frame");
                System.out.println("PASS mpv independent video playback position=" + position + " frames=" + output.frames.get() + " decoder=" + player.getPropertyString("video-codec"));
            } finally { player.detachSurface(); player.destroy(); }
        }
        System.out.println("PASS mpv independent release");
        System.exit(0);
    }
}
