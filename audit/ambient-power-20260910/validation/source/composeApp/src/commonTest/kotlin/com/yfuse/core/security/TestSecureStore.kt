package com.yfuse.core.security

/** Deterministic in-memory secure-store double. It never writes secret bytes into Settings. */
class TestSecureStore : SecureStore {
    private val values = linkedMapOf<String, ByteArray>()
    val corruptedKeys = mutableSetOf<String>()
    var failWrites: Boolean = false

    override fun get(key: String): ByteArray? {
        if (key in corruptedKeys) throw SecureStoreCorruptedException("test corruption")
        return values[key]?.copyOf()
    }

    override fun put(
        key: String,
        value: ByteArray,
    ) {
        if (failWrites) throw SecureStoreException("test write failure")
        values[key] = value.copyOf()
    }

    override fun remove(key: String): Boolean = values.remove(key)?.also { it.fill(0) } != null

    override fun clear() {
        values.values.forEach { it.fill(0) }
        values.clear()
        corruptedKeys.clear()
    }

    fun storedKeys(): Set<String> = values.keys.toSet()
}
