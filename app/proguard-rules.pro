# Keep JavascriptInterface annotations and bridge methods intact under R8 minification
-keepattributes JavascriptInterface
-keepattributes *Annotation*

-keep class com.smouha.stock.MainActivity { *; }
-keep class com.smouha.stock.MainActivity$SmouhaNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.smouha.stock.MainActivity$SmouhaNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
    public *;
}

# Keep org.json classes used by the native bridge
-keep class org.json.** { *; }
