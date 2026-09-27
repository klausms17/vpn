package libxray;
// Stub of the gomobile binding (signatures as gomobile generates them).
public abstract class Libxray {
    public static final String GeositeCodes = "";
    public static final String GeoipCodes = "";
    public static final String DefaultTestURL = "";
    public static void initEnv(String assetDir) {}
    public static void setCrashLog(String path) throws Exception {}
    public static String version() { return ""; }
    public static String parseLink(String link) throws Exception { throw new Exception(); }
    public static String parseSubscription(byte[] body) throws Exception { throw new Exception(); }
    public static String fetchCertSha256(String host, int port, String serverName, boolean useQuic, int timeoutMs) throws Exception { throw new Exception(); }
    public static String pinCertificate(String profileJSON, String sha256Hex) throws Exception { throw new Exception(); }
    public static String buildConfig(String optionsJSON) throws Exception { throw new Exception(); }
    public static String buildProxyOnlyConfig(String outboundsJSON) throws Exception { throw new Exception(); }
    public static String tunSettings(boolean ipv6) { return ""; }
    public static long measureOutboundDelay(String configJSON, String url, int timeoutMs) throws Exception { throw new Exception(); }
    public static FetchResult fetch(String url, String userAgent, int timeoutMs, String proxyConfigJSON) throws Exception { throw new Exception(); }
    public static FetchResult fetchWithHeaders(String url, String userAgent, String headersJSON, int timeoutMs, String proxyConfigJSON) throws Exception { throw new Exception(); }
    public static void downloadFile(String url, String dst, String userAgent, int timeoutMs, String proxyConfigJSON) throws Exception { throw new Exception(); }
    public static void checkGeoFile(String path, String codes) throws Exception {}
    public static void trimGeoFile(String src, String dst, String codes) throws Exception {}
    public static int hostInGeoIP(String path, String code, String host, int timeoutMs) throws Exception { return 0; }
    public static void validateConfig(String configJSON) throws Exception {}
    public static Controller newController() { return new Controller(); }
}
