# R8 rules for the release build. Libraries (Media3, Compose, Glance, Ktor,
# kotlinx.serialization, Coil) ship their own; these cover what R8 can't see.

# Named only in the manifest's meta-data (Cast reads the class name at run time).
-keep class gg.dekiru.musicplayer.android.cast.CastOptionsProvider { *; }

# Glance creates widget button callbacks from their class names.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# kotlinx.serialization: keep the generated serializers of our @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class gg.dekiru.musicplayer.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class gg.dekiru.musicplayer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class gg.dekiru.musicplayer.**$$serializer { *; }

# Ktor and its engines reference optional classes that aren't on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
