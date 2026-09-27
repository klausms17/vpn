package android.os;

/** Test-runtime stand-in, like unitTests.isReturnDefaultValues in the app build. */
public final class SystemClock {
    public static long elapsedRealtime() { return 0; }
    public static long uptimeMillis() { return 0; }
    public static long elapsedRealtimeNanos() { return 0; }
    public static long currentThreadTimeMillis() { return 0; }
}
