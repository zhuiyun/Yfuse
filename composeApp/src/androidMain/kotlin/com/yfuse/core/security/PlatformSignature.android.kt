package com.yfuse.core.security

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual fun verifyEd25519Signature(
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
