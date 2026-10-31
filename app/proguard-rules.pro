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
