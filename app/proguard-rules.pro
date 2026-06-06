# Keep the main class used by app_process
-keep class com.febricahyaa.synthesiscore.MainKt {
    public static void main(java.lang.String[]);
}

# Keep all public classes and methods
-keep class com.febricahyaa.synthesiscore.** { *; }

# Keep hiddenapibypass
-keep class org.lsposed.hiddenapibypass.** { *; }

# Prevent obfuscation of anything accessed via reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions