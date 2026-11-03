package com.russhwolf.settings
// In-memory Settings storage only; the production store owns all merge and serialization logic.
interface Settings {
 fun getStringOrNull(key: String): String?
 fun putString(key: String, value: String)
 fun getIntOrNull(key: String): Int?
 fun putInt(key: String, value: Int)
 fun getLong(key: String, defaultValue: Long): Long
 fun putLong(key: String, value: Long)
 fun remove(key: String)
}
class MapSettings : Settings {
 private val data = mutableMapOf<String, Any>()
 override fun getStringOrNull(key: String) = data[key] as? String
 override fun putString(key: String, value: String) { data[key] = value }
 override fun getIntOrNull(key: String) = data[key] as? Int
 override fun putInt(key: String, value: Int) { data[key] = value }
 override fun getLong(key: String, defaultValue: Long) = data[key] as? Long ?: defaultValue
 override fun putLong(key: String, value: Long) { data[key] = value }
 override fun remove(key: String) { data.remove(key) }
}
