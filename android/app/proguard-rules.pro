# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.lifeos.app.data.remote.dto.** { *; }

# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
# The suspend upload method exposes this type only through its Continuation signature.
-keep,allowobfuscation class com.lifeos.app.places.data.MovementUploadResultDto { *; }
