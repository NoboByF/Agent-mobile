# Keep app classes and Android components safe under R8 minification
-keep class com.example.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# OkHttp3 & Okio optional platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
