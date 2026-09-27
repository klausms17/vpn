package com.klausms.vpn.service;
public interface IVpnController extends android.os.IInterface {
    void registerCallback(IVpnCallback callback) throws android.os.RemoteException;
    void unregisterCallback(IVpnCallback callback) throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IVpnController {
        public android.os.IBinder asBinder() { return this; }
        public static IVpnController asInterface(android.os.IBinder obj) { return null; }
    }
}
