package androidx.compose.runtime

// JVM-only state storage for command ownership tests. No Compose rendering is simulated.
import kotlin.reflect.KProperty
annotation class Stable
class MutableState<T>(var value: T)
fun <T> mutableStateOf(value: T) = MutableState(value)
operator fun <T> MutableState<T>.getValue(receiver: Any?, property: KProperty<*>) = value
operator fun <T> MutableState<T>.setValue(receiver: Any?, property: KProperty<*>, value: T) { this.value = value }
