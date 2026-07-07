# JNA needs its native-mapped classes and the direct-mapping method signatures intact.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# Our native bridge class is referenced by name from libryubingjni.so (JNI).
-keep class org.ryubing.android.RyubingNative { *; }
