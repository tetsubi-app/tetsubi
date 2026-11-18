# ProGuard rules for the desktop release build (packageRelease*).
#
# OkHttp (under Ktor) can use these TLS providers and native-image hooks when
# they're present; Tetsubi doesn't ship them, so their references are expected
# to be missing. Without these lines ProGuard stops on "unresolved references".
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.**
