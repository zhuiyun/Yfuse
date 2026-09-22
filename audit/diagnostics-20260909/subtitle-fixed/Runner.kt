fun main() {
    var count = 0
    listOf(com.yfuse.core2.subtitle.YSubtitleCueBufferTest(), com.yfuse.core2.android.AndroidFfmpegDemuxerMappingTest()).forEach { instance ->
        instance.javaClass.declaredMethods.filter { it.parameterCount == 0 && it.returnType == Void.TYPE }.forEach { method ->
            method.invoke(instance)
            println("PASS ${method.name}")
            count++
        }
    }
    println("PASS $count production Kotlin subtitle regressions")
}