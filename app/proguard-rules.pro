# R8 rules for SynthesisCore.
#
# The APK is never installed; app_process loads its dex and calls MainKt.main().
# That entry point is the only name that must survive. Everything else is
# shrunk, optimised and obfuscated, which makes the shipped APK much harder to
# reverse engineer or patch. Framework classes reached by reflection (AIDL stubs,
# @hide listeners) live in the platform, not in this APK, so they are unaffected.

# Entry point used by app_process
-keep class com.febricahyaa.synthesiscore.MainKt {
    public static void main(java.lang.String[]);
}

# HiddenApiBypass looks up its own members reflectively
-keep class org.lsposed.hiddenapibypass.** { *; }

# Move all obfuscated classes into one short package
-repackageclasses 'sc'
-allowaccessmodification

# Keep line numbers so stack traces in sysmon.log can be retraced with the
# mapping.txt archived by the release workflow; hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SC
