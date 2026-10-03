import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.Origin;
import java.util.Arrays;

/**
 * Runs R8 on the same arguments as its command line, plus the setting an R8 dump from AGP records
 * that the command line cannot express: missing library API modeling, which moves calls to APIs
 * newer than minSdk out of the methods that make them and so changes those methods' code.
 *
 * usage: java -cp r8lib.jar ReplayR8.java [--missing-library-api-modeling] <R8 arguments>...
 */
public final class ReplayR8 {
    public static void main(String[] args) throws Exception {
        boolean apiModeling = args.length > 0 && args[0].equals("--missing-library-api-modeling");
        String[] r8Arguments = apiModeling ? Arrays.copyOfRange(args, 1, args.length) : args;
        R8Command.Builder builder = R8Command.parse(r8Arguments, Origin.root());
        builder.setEnableExperimentalMissingLibraryApiModeling(apiModeling);
        R8.run(builder.build());
    }
}
