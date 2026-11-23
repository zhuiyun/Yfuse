import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

class RepeatStartupRanges {
    public static void main(String[] args) throws Exception {
        Class<?> suite = Class.forName("com.yfuse.core2.android.YCoreStartupRangeHttpTest");
        Class<?> source = Class.forName("com.yfuse.core2.android.AndroidTransportMediaDataSource");
        try (InputStream compiled = source.getResourceAsStream("AndroidTransportMediaDataSource.class")) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compiled.readAllBytes()));
            System.out.println("{\"compiledTransportClassSha256\":\"" + hash + "\"}");
        }
        int failures = 0;
        for (int round = 1; round <= Integer.parseInt(args[0]); round++) {
            Result result = new JUnitCore().run(suite);
            if (result.getRunCount() != 2 || result.getIgnoreCount() != 0) throw new AssertionError("Missing socket regression");
            failures += result.getFailureCount();
            System.out.printf("{\"round\":%d,\"tests\":%d,\"failed\":%d,\"milliseconds\":%d}%n",
                              round, result.getRunCount(), result.getFailureCount(), result.getRunTime());
            for (var failed : result.getFailures()) System.err.println(failed.toString());
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
