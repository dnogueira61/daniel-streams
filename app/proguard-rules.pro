# ProGuard rules for DLivePTStream
-keep class com.dlive.ptstream.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
