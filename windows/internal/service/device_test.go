package service

import (
	"path/filepath"
	"regexp"
	"testing"

	"github.com/klausms17/vpn/libxray/client/subscription"
)

func TestDeviceIDHashesMachineGUID(t *testing.T) {
	id, err := deviceID("5f0c4d9e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", filepath.Join(t.TempDir(), "hwid"))
	if err != nil || id != subscription.HashID("5f0c4d9e-1a2b-4c3d-8e9f-0a1b2c3d4e5f") {
		t.Fatalf("got %q, %v", id, err)
	}
}

func TestDeviceIDWithoutMachineGUIDIsKept(t *testing.T) {
	file := filepath.Join(t.TempDir(), "hwid")
	first, err := deviceID(" ", file)
	if err != nil || !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(first) {
		t.Fatalf("got %q, %v", first, err)
	}
	again, err := deviceID("", file)
	if err != nil || again != first {
		t.Fatalf("got %q, %v; want %q", again, err, first)
	}
}
