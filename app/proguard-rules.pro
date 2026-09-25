# gomobile: Go calls back into these classes through JNI by name.
-keep class go.** { *; }
-keep class libxray.** { *; }

# AIDL stubs are referenced by the binder framework.
-keep class com.klausms.vpn.service.IVpn** { *; }
