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
# Belt-and-suspenders: keep everything serializable in our own package.
-keep @kotlinx.serialization.Serializable class com.yfuse.** { *; }
-keepclassmembers class com.yfuse.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Ktor / coroutines ----
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# ---- Enums used by libraries via valueOf ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
