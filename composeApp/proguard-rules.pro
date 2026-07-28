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

# ---- Enums used by libraries via valueOf ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
