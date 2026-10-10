import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

class VerifyScheduleSignature {
    public static void main(String[] args) throws Exception {
        var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
            Base64.getDecoder().decode("MCowBQYDK2VwAyEALedMp5RHVd/Cw9v26cP50e0F8hFqKQ/SXm9J9T3oU/4=")));
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(Files.readAllBytes(Path.of(args[0])));
        if (!verifier.verify(Files.readAllBytes(Path.of(args[1])))) {
            throw new SecurityException("Calendar signature invalid");
        }
        System.out.println("Public calendar signature VERIFIED with app Ed25519 key");
    }
}
