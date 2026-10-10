import java.util.Arrays;

public final class ProbeEntry {
    public static void main(String[] args) {
        try {
            Class.forName(args[0]).getMethod("main", String[].class)
                .invoke(null, (Object)Arrays.copyOfRange(args, 1, args.length));
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            cause.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
