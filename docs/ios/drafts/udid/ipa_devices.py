#!/usr/bin/env python3
"""Which devices can install this .ipa, and until when (stdlib only).

Reads Payload/*.app/embedded.mobileprovision (and each PlugIns/*.appex one):
the app installs on a device only if its UDID is in EVERY one of them.
Prints counts only; --has UDID answers yes/no without printing the list.
"""
import re, sys, zipfile
from cms_plist import signed_plist

APP_PROV = re.compile(r"^Payload/[^/]+\.app/(?:PlugIns/[^/]+\.appex/)?embedded\.mobileprovision$")

def ipa_profiles(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if APP_PROV.match(info.filename) and info.file_size < 1 << 20:
                out[info.filename] = signed_plist(z.read(info))
    if not out:
        raise SystemExit("no embedded.mobileprovision in the ipa")
    return out

def installable(profiles):
    sets = [set(u.lower() for u in p.get("ProvisionedDevices", [])) for p in profiles.values()]
    devices = set.intersection(*sets)
    expires = min(p["ExpirationDate"] for p in profiles.values())
    return devices, expires

if __name__ == "__main__":
    profiles = ipa_profiles(sys.argv[1])
    devices, expires = installable(profiles)
    if len(sys.argv) > 3 and sys.argv[2] == "--has":
        print("yes" if sys.argv[3].lower() in devices else "no")
    else:
        print("%d profiles, %d devices, expires %s" % (len(profiles), len(devices), expires.isoformat()))
