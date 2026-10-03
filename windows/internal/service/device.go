package service

import (
	"os"
	"strings"

	"github.com/klausms17/vpn/libxray/client/subscription"
)

// deviceID is the id subscription requests give the panel for its device
// limit: the MachineGuid of Windows hashed as the Android app hashes its
// ANDROID_ID, or, when Windows has none, a random one kept in file.
func deviceID(machineGUID, file string) (string, error) {
	if strings.TrimSpace(machineGUID) != "" {
		return subscription.HashID(machineGUID), nil
	}
	if data, err := os.ReadFile(file); err == nil {
		if id := strings.TrimSpace(string(data)); id != "" {
			return id, nil
		}
	}
	id := subscription.HashID(newID())
	return id, os.WriteFile(file, []byte(id), 0o600)
}
