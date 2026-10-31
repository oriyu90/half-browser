# Keep AndroidX WebView callbacks that are reached through reflection.
-keep class androidx.webkit.** { *; }
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
-keepattributes *Annotation*

# kotlinx.serialization
-keepattributes InnerClasses,EnclosingMethod
-keep,includedescriptorclasses class com.yukiorita.halfbrowser.**$$serializer { *; }
-keepclassmembers class com.yukiorita.halfbrowser.** {
    *** Companion;
}
-keepclasseswithmembers class com.yukiorita.halfbrowser.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (used by androidx.security:security-crypto) references annotation-only
# classes at compile time and loads primitives reflectively. Without these rules
# R8 fails the release build or strips a primitive and breaks credential storage.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# WorkManager initializes its Room database and Worker classes by reflection.
# Without these rules R8 removes the generated no-arg constructors and the
# release build crashes during Startup (NoSuchMethodException WorkDatabase_Impl).
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.**
-keep class com.yukiorita.halfbrowser.work.SiteWatchWorker { <init>(...); }
