package com.klausms.vpn.service;

import com.klausms.vpn.service.IVpnCallback;

// Exposed by XrayVpnService to the app's own UI process only.
interface IVpnController {
    void registerCallback(IVpnCallback callback);
    void unregisterCallback(IVpnCallback callback);
    // Latency in ms through the running tunnel, or -1 on failure.
    long testConnection();
}
