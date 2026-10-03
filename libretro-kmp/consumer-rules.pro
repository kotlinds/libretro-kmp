# The JNI bridge (libretro_jni.c) looks these up by name: keep them in apps using R8/ProGuard.
-keep class dev.kotlinds.libretrokmp.LibretroCore$Callbacks { *; }
-keepclasseswithmembernames class dev.kotlinds.libretrokmp.LibretroJni { native <methods>; }
