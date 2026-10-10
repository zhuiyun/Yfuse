import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.common.TrackSelectionParameters;

public final class ExoProbe {
    public static void main(String[] args) throws Exception {
        Context context = ProbeSupport.context(args[0]);
        ProbeSupport.Output output = new ProbeSupport.Output(android.graphics.ImageFormat.PRIVATE);
        // No-context selection avoids Settings-provider calls from an unregistered shell process.
        ExoPlayer player = new ExoPlayer.Builder(context)
            .setTrackSelector(new DefaultTrackSelector(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, new AdaptiveTrackSelection.Factory()))
            .build();
        Handler handler = new Handler(Looper.getMainLooper());
        player.setVolume(0);
        player.setVideoSurface(output.reader.getSurface());
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                error.printStackTrace(); System.exit(1);
            }
        });
        player.setMediaItem(MediaItem.fromUri(args[1]));
        player.prepare();
        player.play();
        long deadline = System.currentTimeMillis() + 20000;
        Runnable poll = new Runnable() {
            public void run() {
                if (player.getCurrentPosition() > 300 && output.frames.get() > 0) {
                    System.out.println("PASS exo independent video playback position=" + player.getCurrentPosition() + " frames=" + output.frames.get());
                    player.release(); output.close();
                    System.out.println("PASS exo independent release");
                    System.exit(0);
                }
                if (System.currentTimeMillis() > deadline) {
                    System.err.println("FAIL exo no output state=" + player.getPlaybackState() + " frames=" + output.frames.get());
                    player.release(); output.close(); System.exit(1);
                }
                handler.postDelayed(this, 50);
            }
        };
        handler.post(poll);
        Looper.loop();
    }
}
