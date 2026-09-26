package com.klausms.vpn.service;

// Delivered by the VPN process to the UI process.
oneway interface IVpnCallback {
    void onStatus(int state, String profileId, String profileName, String message, long connectedSince);
    void onTraffic(long upRate, long downRate, long upTotal, long downTotal);
    // The VPN process changed the saved servers or the selection (oneway,
    // like every call here): the UI reloads them.
    void onProfilesChanged();
}
