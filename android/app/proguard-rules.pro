# Sherpa-ONNX JNI reads Kotlin data-class fields by name (GetFieldID).
# R8 must not strip or rename them or ASR dies with:
#   Failed to get field ID for decodingMethod
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Keep onnxruntime if present
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
