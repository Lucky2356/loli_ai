# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.loli.**$$serializer { *; }
-keepclassmembers class ai.loli.** { *** Companion; }
-keepclasseswithmembers class ai.loli.** { kotlinx.serialization.KSerializer serializer(...); }

# SQLCipher
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# Vosk + JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**

# Shizuku: команды оболочки через newProcess (вызывается рефлексией)
-keepclassmembers class rikka.shizuku.Shizuku { *** newProcess(...); }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

# sherpa-onnx (встроенный голос): нативный код читает поля конфигурации по именам через JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
