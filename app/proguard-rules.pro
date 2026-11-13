-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class com.vidlab.app.**$$serializer { *; }
-keepclassmembers class com.vidlab.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.vidlab.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.jellyfin.media3.**
-dontwarn okhttp3.**
-dontwarn okio.**
