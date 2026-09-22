# Android, Cast and WorkManager instantiate these from class names rather than ordinary calls.
-keep class com.yfuse.tv.TvApplication { *; }
-keep class com.yfuse.tv.TvMainActivity { *; }
-keep class com.yfuse.feature.player.PlayerActivity { *; }
-keep class com.yfuse.feature.player.PlaybackKeepAliveService { *; }
-keep class com.yfuse.core.cast.YfuseCastOptionsProvider { *; }
-keep class com.yfuse.tv.integration.YfuseCastReceiverOptionsProvider { *; }
-keep class com.yfuse.tv.integration.TvContinueWatchingSyncWorker { *; }

# kotlinx.serialization generated entry points used by persisted navigation, server models and
# credential-free Continue Watching snapshots.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers,allowshrinking class **$$serializer {
    *** INSTANCE;
}

-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# R8 full-mode argument removal can otherwise desynchronise MVIKotlin's AtomicRef interface on
# older TV devices.
-keep,allowobfuscation class com.arkivanov.mvikotlin.core.utils.internal.Atomic** { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Phone/legacy adapters are compiled only to keep one shared source tree. The TV runtime selects
# the YCore system-native path before any of these optional types are loaded.
-dontwarn androidx.camera.**
-dontwarn com.google.zxing.**
-dontwarn com.mediadevkit.sdk.**
-dontwarn dev.jdtech.mpv.**
