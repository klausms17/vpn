# jvm-check

A quick check of the Android app's plain-Kotlin code where there is no
Android SDK (cloud sessions): it compiles `app/src/main/java` without the
Compose UI against `android-all` plus the stubs here, and runs the JVM unit
tests from `app/src/test/java` (all but `ScreenshotTest`).

    gradle -p tools/jvm-check test

It does not replace CI: Compose screens, resources, lint and the APK are
built only there (`.github/workflows/android.yml`).

Keep the stubs in step with the code they stand for:
- `stubs/libxray/*.java`: the gomobile binding of `libxray/` (Java names).
- `stubs/com/klausms/vpn/service/IVpn*.java`: the AIDL files in
  `app/src/main/aidl`.
- `stubs/com/klausms/vpn/R.java`, `BuildConfig.java`: the ids the plain code
  uses.
- `stubs/com/klausms/vpn/ui/MainActivityStub.kt`: the constants of the
  excluded `MainActivity`.
