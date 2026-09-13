# MultiVPN Android — R8 keep rules.
#
# Two native cores live in this app and BOTH reach Kotlin/Java only through
# reflection or JNI, which R8 cannot see:
#  - libbox (hiddify-core AAR): gomobile binds Go structs to Java classes; the
#    generated bindings are invoked from Go by name.
#  - libovpn3 (tim06 openvpn AAR): the OpenVPN 3 C++ core calls back into
#    ClientAPI_* JNI classes.
# Stripping either one produces an app that installs fine and dies in onCreate
# — the exact class of silent failure the project's honesty rules forbid, so
# the rules err on the side of keeping.

# --- gomobile / libbox -------------------------------------------------------
-keep class com.hiddify.core.** { *; }
-dontwarn com.hiddify.core.**

# --- OpenVPN 3 client (libovpn3.so + management layer) -----------------------
-keep class com.tim.openvpn.** { *; }
-keep class com.tim.basevpn.** { *; }
-keep class net.openvpn.ovpn3.** { *; }
-dontwarn com.tim.openvpn.**
-dontwarn com.tim.basevpn.**

# --- kotlinx.serialization ----------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class vpn.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class vpn.core.**$$serializer { *; }
-keepclassmembers class vpn.core.** {
    *** Companion;
}

# --- jsch ---------------------------------------------------------------------
-dontwarn com.jcraft.jsch.**
-keep class com.jcraft.jsch.** { *; }

# --- coroutines debug metadata (small; keep for honest stack traces) ----------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
