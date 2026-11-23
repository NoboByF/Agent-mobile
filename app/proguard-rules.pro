# Keep app classes and Android components safe under R8 minification
-keep class com.example.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# OkHttp3 & Okio optional platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# JSch (com.github.mwiede:jsch) loads crypto, KEX, and auth providers dynamically via Class.forName
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# BouncyCastle (X25519, BLAKE2s, ChaCha20-Poly1305, JSch BC provider)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

