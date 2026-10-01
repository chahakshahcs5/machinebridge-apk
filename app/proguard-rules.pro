# Proguard rules for MachineBridge
-keepclassmembers class * {
    native <methods>;
}

-keep class com.machinebridge.app.** { *; }
-keep class com.machinebridge.app.NativeBridge { *; }
-keepclassmembers class com.machinebridge.app.NativeBridge {
    public static java.lang.String downloadFile(java.lang.String, java.lang.String);
    native <methods>;
}
