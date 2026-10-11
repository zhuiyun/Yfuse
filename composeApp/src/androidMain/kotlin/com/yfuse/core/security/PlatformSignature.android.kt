package com.yfuse.core.security

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

actual fun verifyEd25519Signature(
    publicKeyBase64: String,
    payload: ByteArray,
    signatureBase64: String,
): Boolean =
    verifyWithPlatformProvider(publicKeyBase64, payload, signatureBase64) ||
        verifyWithBundledProvider(publicKeyBase64, payload, signatureBase64)

private fun verifyWithPlatformProvider(
    publicKeyBase64: String,
    payload: ByteArray,
    signatureBase64: String,
): Boolean =
    runCatching {
        val publicKey =
            KeyFactory
                .getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(decodeBase64(publicKeyBase64)))
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(payload)
            verify(decodeBase64(signatureBase64))
        }
    }.getOrDefault(false)

/** API 26–32 fallback; Android's built-in Ed25519 Signature implementation starts at API 33. */
private fun verifyWithBundledProvider(
    publicKeyBase64: String,
    payload: ByteArray,
    signatureBase64: String,
): Boolean =
    runCatching {
        val encodedKey = decodeBase64(publicKeyBase64)
        val publicKey = PublicKeyFactory.createKey(encodedKey) as Ed25519PublicKeyParameters
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(payload, 0, payload.size)
        verifier.verifySignature(decodeBase64(signatureBase64))
    }.getOrDefault(false)

/** Android's DEFAULT decoder accepts wrapped input; keep that compatibility on the JVM. */
private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value.filterNot(Char::isWhitespace))
