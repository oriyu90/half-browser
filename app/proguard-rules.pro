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
