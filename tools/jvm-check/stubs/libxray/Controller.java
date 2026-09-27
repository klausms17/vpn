package libxray;
public final class Controller {
    public native void start(String configJSON, int tunFd) throws Exception;
    public native void stop() throws Exception;
    public native boolean isRunning();
    public native long measureDelay(String url, int timeoutMs) throws Exception;
    public native FetchResult fetchThroughTunnel(String url, String userAgent, String headersJSON, int timeoutMs) throws Exception;
    public native String probeOutbounds(String candidatesJSON, String url, int timeoutMs, int parallel) throws Exception;
}
