# Room generates implementations reflectively looked up by name at runtime.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization keeps generated serializers on the companion; R8 cannot see the link.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.sergio.lifeinsights.** {
    *** Companion;
}
-keepclasseswithmembers class dev.sergio.lifeinsights.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.sergio.lifeinsights.**$$serializer { *; }

# WorkManager instantiates workers by class name from the database.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
