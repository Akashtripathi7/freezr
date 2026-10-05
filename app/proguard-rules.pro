# Freezr R8 rules. Room, Hilt, WorkManager, Compose, Glance and kotlinx.serialization ship their own
# consumer rules; only app-specific reflection needs to be listed here.

# Glance instantiates ActionCallback implementations reflectively by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Selector rules JSON models (kotlinx.serialization generated serializers).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.freezr.app.platform.rules.**$$serializer { *; }
-keepclassmembers class com.freezr.app.platform.rules.** {
    *** Companion;
}
-keepclasseswithmembers class com.freezr.app.platform.rules.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose logging from release builds (screen content is never logged in any build).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
