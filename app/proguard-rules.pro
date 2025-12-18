# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Keep Zebra SDK classes
-keep class com.zebra.sdk.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
