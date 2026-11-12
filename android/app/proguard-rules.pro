# Grabbly release build rules.
# Room, WorkManager, OkHttp, Coil and kotlinx.serialization ship their own R8 rules.

# Keep the generated serializers for the API models (belt and braces for older R8 versions)
-keep,includedescriptorclasses class com.grabbly.downloader.data.**$$serializer { *; }
-keepclassmembers class com.grabbly.downloader.data.** {
    *** Companion;
}
