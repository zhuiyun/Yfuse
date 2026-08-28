package com.yfuse.core.security

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory

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
                .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }
    }.getOrDefault(false)

/** API 26–32 fallback; Android's built-in Ed25519 Signature implementation starts at API 33. */
private fun verifyWithBundledProvider(
    publicKeyBase64: String,
    payload: ByteArray,
    signatureBase64: String,
): Boolean =
    runCatching {
        val encodedKey = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val publicKey = PublicKeyFactory.createKey(encodedKey) as Ed25519PublicKeyParameters
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(payload, 0, payload.size)
        verifier.verifySignature(Base64.decode(signatureBase64, Base64.DEFAULT))
    }.getOrDefault(false)
