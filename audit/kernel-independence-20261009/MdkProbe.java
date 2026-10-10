import com.mediadevkit.sdk.MDKPlayer;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import android.os.Handler;
import android.os.Looper;

public final class MdkProbe {
    public static void main(String[] args) throws Exception {
        ProbeSupport.context(args[0]);
        ProbeSupport.load(args[0]);
        ProbeSupport.TextureOutput output = new ProbeSupport.TextureOutput();
        MDKPlayer player = new MDKPlayer();
            Field ptr = MDKPlayer.class.getDeclaredField("nativePtr");
            ptr.setAccessible(true);
            Method setSurface = MDKPlayer.class.getDeclaredMethod("nativeSetSurface", long.class, Surface.class, int.class, int.class);
            setSurface.setAccessible(true);
            setSurface.invoke(null, ptr.getLong(player), output.surface, 320, 180);
            player.setDecoderMode(1);
            player.setActiveTrack(MDKPlayer.MEDIA_TYPE_AUDIO, -1);
            player.setMedia(args[1]);
            player.setState(MDKPlayer.STATE_PLAYING);
            long deadline = System.currentTimeMillis() + 20000;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable poll = new Runnable() {
            public void run() {
                try {
                    ProbeSupport.check((player.mediaStatus() & MDKPlayer.STATUS_INVALID) == 0, "MDK media invalid: " + player.lastError());
                    if (player.position() > 300 && output.frames.get() > 0) {
                        System.out.println("PASS mdk independent video playback version=" + MDKPlayer.runtimeVersion() + " position=" + player.position() + " frames=" + output.frames.get() + " evidence=" + Arrays.toString(player.playbackEvidence()));
                        player.close(); output.close();
                        System.out.println("PASS mdk independent release"); System.exit(0);
                    }
                    if (System.currentTimeMillis() > deadline) {
                        throw new AssertionError("MDK no output: status=" + player.mediaStatus() + " position=" + player.position() + " frames=" + output.frames.get() + " evidence=" + Arrays.toString(player.playbackEvidence()));
                    }
                    handler.postDelayed(this, 50);
                } catch (Throwable error) { error.printStackTrace(); player.close(); output.close(); System.exit(1); }
            }
        };
        handler.post(poll);
        // MDK's Android renderer is driven by Choreographer callbacks on this Looper.
        Looper.loop();
    }
}
