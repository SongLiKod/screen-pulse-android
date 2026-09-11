# ProGuard rules for ScreenPulse
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.screenpulse.jni.** { *; }
