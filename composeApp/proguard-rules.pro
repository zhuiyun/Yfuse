# ---- Android entry points ----
# These classes are instantiated by Android or Google Cast from Manifest strings rather
# than from bytecode references. Keep them explicitly: relying on AGP's generated AAPT
# rules left the release DEX without the application and every app component.
-keep class com.yfuse.YfuseApp { *; }
-keep class com.yfuse.MainActivity { *; }
-keep class com.yfuse.feature.player.PlayerActivity { *; }
-keep class com.yfuse.feature.profile.QrScannerActivity { *; }
-keep class com.yfuse.core.offline.OfflineDownloadService { *; }
-keep class com.yfuse.update.UpdateDownloadService { *; }
-keep class com.yfuse.feature.player.PlaybackKeepAliveService { *; }
-keep class com.yfuse.core.cast.YfuseCastOptionsProvider { *; }

# ---- kotlinx.serialization ----
# Keep the generated serializers for our @Serializable model/DTO classes,
# otherwise R8 strips them and JSON parsing throws at runtime.
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
# Generated serializers are referenced directly by the compiler plugin.
# Keeping every field of every DTO/model prevented R8 from renaming and
# shrinking a large part of the data layer, so only serializer entry points
# above are retained.

# ---- Ktor / coroutines ----
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# R8 full-mode argument removal can desynchronize MVIKotlin's generic AtomicRef interface
# from its generated JVM implementation on Android 9, causing AbstractMethodError at startup.
-keep,allowobfuscation class com.arkivanov.mvikotlin.core.utils.internal.Atomic** { *; }

# ---- Enums used by libraries via valueOf ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# The custom libmpv bridge is discovered through Class.forName and invokes these members by name.
-keep class dev.yfuse.mpv.YfuseMpvCapabilities { *; }
-keep class dev.yfuse.mpv.YfuseBluRayRegistry { *; }
-keep class dev.yfuse.mpv.YfuseBdmvRegistry { *; }
