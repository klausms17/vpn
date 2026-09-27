package libxray;
public final class FetchResult {
    public final native byte[] getBody();
    public final native void setBody(byte[] v);
    public final native String getUserInfo();
    public final native String getProfileTitle();
    public final native String getUpdateInterval();
    public final native String getSupportUrl();
    public final native String getWebPageUrl();
    public final native String getAnnounce();
    public final native String getReportUrl();
    public final native String getAppUrl();
    public final native boolean getHwidActive();
    public final native boolean getHwidLimit();
    public final native boolean getHwidMaxDevices();
    public final native boolean getHwidNotSupported();
}
