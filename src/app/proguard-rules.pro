# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve source locations for retraced Play Console stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# These generated receiver names are stored as strings and loaded reflectively.
-keepnames class com.feldman.ha.widgets.generated.**

# Gson creates Home Assistant entities reflectively from unannotated field names.
-keep class com.feldman.ha.data.HAEntity { *; }

# Gson creates Frigate API models reflectively from unannotated field names.
-keep class com.feldman.ha.api.FrigateConfig { *; }
-keep class com.feldman.ha.api.CameraConfig { *; }
-keep class com.feldman.ha.api.FrigateEvent { *; }
-keep class com.feldman.ha.api.FrigateRecordingDay { *; }
-keep class com.feldman.ha.api.FrigateRecordingHour { *; }
# Do not ship entity names, states, service payloads, URLs, or diagnostics in logcat.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}