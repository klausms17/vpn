package com.klausms.vpn.service;
public interface IVpnCallback extends android.os.IInterface {
    void onStatus(int state, String profileId, String profileName, String message, long connectedSince) throws android.os.RemoteException;
    void onProfilesChanged() throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IVpnCallback {
        public android.os.IBinder asBinder() { return this; }
    }
}
