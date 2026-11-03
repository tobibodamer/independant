# ONNX Runtime calls back into Java by name over JNI.
-keep class ai.onnxruntime.** { *; }

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
