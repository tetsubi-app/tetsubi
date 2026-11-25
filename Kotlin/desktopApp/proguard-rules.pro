# ProGuard rules for the desktop release build (packageRelease*).
# ProGuard is switched off in build.gradle.kts for now (see there); these
# rules got it to build, but the shrunk app couldn't reach the server.
#
# OkHttp (under Ktor) can use these TLS providers and native-image hooks when
# they're present; Tetsubi doesn't ship them, so their references are expected
# to be missing. Without these lines ProGuard stops on "unresolved references".
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.**

# D-Bus (MPRIS, the keyring): dbus-java builds calls and signals by
# reflection from the interfaces' names, methods and constructors.
-keep class org.freedesktop.dbus.** { *; }
-keep class gg.dekiru.musicplayer.playback.mpris.** { *; }
-keep class gg.dekiru.musicplayer.data.auth.Secret** { *; }
-keep class gg.dekiru.musicplayer.data.auth.TwoValues { *; }
-dontwarn org.slf4j.**
