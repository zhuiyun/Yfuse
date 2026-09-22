fun main() {
    var count = 0
    listOf(com.yfuse.core2.android.AndroidProbeBudgetTest(), com.yfuse.core2.android.AndroidBoundedProbeTest(), com.yfuse.core.playback.AndroidBlockingMediaProbeLaneTest()).forEach { instance ->
        instance.javaClass.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE }.forEach { method ->
            method.invoke(instance)
            println("PASS ${method.name}")
            count++
        }
    }
    println("PASS $count production Kotlin probe regressions")
}