# Keep accessibility service
-keep class com.onesec.app.AppWatcherService { *; }
-keep class com.onesec.app.BootReceiver { *; }
-keep class com.onesec.app.InterventionActivity { *; }

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*, Signature, InnerClasses

# Standard Android rules
-dontwarn kotlin.**
-dontwarn kotlinx.**
