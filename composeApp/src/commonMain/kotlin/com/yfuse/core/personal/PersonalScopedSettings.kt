package com.yfuse.core.personal

import com.russhwolf.settings.Settings

/** Namespaces existing preference stores without copying another profile's private state. */
class PersonalScopedSettings(
    private val delegate: Settings,
    private val namespace: () -> String,
) : Settings {
    private fun key(value: String): String = "personal.scope.${namespace()}.$value"

    override val keys: Set<String> get() {
        val prefix = key("")
        return delegate.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }
    override val size: Int get() = keys.size

    override fun clear() {
        keys.forEach(::remove)
    }

    override fun remove(key: String) = delegate.remove(key(key))

    override fun hasKey(key: String): Boolean = delegate.hasKey(key(key))

    override fun putInt(
        key: String,
        value: Int,
    ) = delegate.putInt(key(key), value)

    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = delegate.getInt(key(key), defaultValue)

    override fun getIntOrNull(key: String): Int? = delegate.getIntOrNull(key(key))

    override fun putLong(
        key: String,
        value: Long,
    ) = delegate.putLong(key(key), value)

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = delegate.getLong(key(key), defaultValue)

    override fun getLongOrNull(key: String): Long? = delegate.getLongOrNull(key(key))

    override fun putString(
        key: String,
        value: String,
    ) = delegate.putString(key(key), value)

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = delegate.getString(key(key), defaultValue)

    override fun getStringOrNull(key: String): String? = delegate.getStringOrNull(key(key))

    override fun putFloat(
        key: String,
        value: Float,
    ) = delegate.putFloat(key(key), value)

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = delegate.getFloat(key(key), defaultValue)

    override fun getFloatOrNull(key: String): Float? = delegate.getFloatOrNull(key(key))

    override fun putDouble(
        key: String,
        value: Double,
    ) = delegate.putDouble(key(key), value)

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ): Double = delegate.getDouble(key(key), defaultValue)

    override fun getDoubleOrNull(key: String): Double? = delegate.getDoubleOrNull(key(key))

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = delegate.putBoolean(key(key), value)

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = delegate.getBoolean(key(key), defaultValue)

    override fun getBooleanOrNull(key: String): Boolean? = delegate.getBooleanOrNull(key(key))
}
