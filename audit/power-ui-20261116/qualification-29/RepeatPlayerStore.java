import java.util.concurrent.TimeUnit;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;

class RepeatPlayerStore {
    public static void main(String[] args) throws Exception {
        Class<?> suite = Class.forName("com.yfuse.feature.player.PlayerStoreTest");
        int rounds = Integer.parseInt(args[0]);
        int failed = 0;
        for (int i = 0; i < rounds; i++) {
            Request request = args[1].equals("all") ? Request.aClass(suite) : Request.method(suite, args[1]);
            Result result = new JUnitCore().run(request);
            if (!result.wasSuccessful()) {
                failed++;
                for (var cause : result.getFailures()) {
                    System.out.println("round=" + (i + 1) + " case=" + cause.getTestHeader() + " exception=" + cause.getException().getClass().getName()
                        + " message=" + cause.getMessage());
                }
            }
            if (i == 0 || (i + 1) % 10 == 0) {
                System.out.println("rounds=" + (i + 1) + " runCount=" + result.getRunCount() + " failedRounds=" + failed);
            }
        }
        if (failed != 0) System.exit(1);
    }
}
