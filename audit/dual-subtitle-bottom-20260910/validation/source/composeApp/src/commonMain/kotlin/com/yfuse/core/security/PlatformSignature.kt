package com.yfuse.core.security

/** Verifies an Ed25519 signature whose public key is X.509 SubjectPublicKeyInfo DER. */
expect fun verifyEd25519Signature(
    publicKeyBase64: String,
    payload: ByteArray,
    signatureBase64: String,
): Boolean
