package android.util;

/** Test-runtime stand-in, like unitTests.isReturnDefaultValues in the app build. */
public final class Log {
    public static int v(String t, String m) { return 0; }
    public static int v(String t, String m, Throwable e) { return 0; }
    public static int d(String t, String m) { return 0; }
    public static int d(String t, String m, Throwable e) { return 0; }
    public static int i(String t, String m) { return 0; }
    public static int i(String t, String m, Throwable e) { return 0; }
    public static int w(String t, String m) { return 0; }
    public static int w(String t, String m, Throwable e) { return 0; }
    public static int w(String t, Throwable e) { return 0; }
    public static int e(String t, String m) { return 0; }
    public static int e(String t, String m, Throwable e) { return 0; }
    public static String getStackTraceString(Throwable e) { return ""; }
    public static boolean isLoggable(String t, int level) { return false; }
}
