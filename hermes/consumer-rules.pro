# :hermes decodes every payload with compile-time serializers (decodeFromString<T>() /
# decodeFromJsonElement<T>()), so nothing is looked up by reflection. These keeps protect the
# generated serializers when an app that depends on :hermes runs R8.
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.folio.hermes.**$$serializer { *; }
-keepclassmembers class com.folio.hermes.** {
    *** Companion;
}
-keepclasseswithmembers class com.folio.hermes.** {
    kotlinx.serialization.KSerializer serializer(...);
}
