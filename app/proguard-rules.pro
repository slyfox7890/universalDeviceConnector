-keep class com.example.universaldeviceconnector.MainActivity$DeviceInfo { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }
-keep class org.json.** { *; }
-keepclassmembers class com.example.universaldeviceconnector.MainActivity$Companion {
    *** DEVICE_SIGNATURES;
    *** COMMON_PORTS;
    *** COMMON_NETWORK_RANGES;
    *** SUPPORTED_CATEGORIES;
}
