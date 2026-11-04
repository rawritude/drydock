# kotlinx.serialization keeps generated serializers via @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.rawritude.drydock.engine.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.rawritude.drydock.engine.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp platform detection warnings.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
