# kotlinx.serialization: keep generated serializers for the provider DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.footor.core.** {
    *** Companion;
}
-keepclasseswithmembers class app.footor.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.footor.core.**$$serializer { *; }

# OkHttp optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
