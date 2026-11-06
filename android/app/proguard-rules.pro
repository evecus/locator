# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class cn.locator.app.**$$serializer { *; }
-keepclassmembers class cn.locator.app.** {
    *** Companion;
}
-keepclasseswithmembers class cn.locator.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- osmdroid ----
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
-dontwarn org.slf4j.**
-dontwarn microsoft.**
-dontwarn com.ibm.**
