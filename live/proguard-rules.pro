# R8 is on for release.
# WebRTC: Java <-> native (JNI) calls are looked up by name from libjingle_peerconnection_so, in
# both directions (@CalledByNative / native methods), so the whole package keeps its names.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.webrtc.**

# kotlinx.serialization: every decode uses a compile-time serializer (decodeFromString<T>()),
# nothing is looked up by reflection. Keeps for the generated serializers, as in :app.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.Required <fields>;
}
-keep,includedescriptorclasses class com.folio.hermeslive.**$$serializer { *; }
-keepclassmembers class com.folio.hermeslive.** {
    *** Companion;
}
-dontwarn org.slf4j.**
