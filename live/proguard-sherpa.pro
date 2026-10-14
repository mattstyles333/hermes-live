# sherpa-onnx (S-C speech-to-text). The JNI library reads the Kotlin config classes' fields and
# builds result objects by name (FindClass / GetFieldID / GetMethodID), so the whole package keeps
# its names and members. Not runtime-checked on arm64 here: verify on the first release run.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# commons-compress: only the tar and bzip2 readers are used; it references optional codecs.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
