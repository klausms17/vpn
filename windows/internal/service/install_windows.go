package service

import (
	"errors"
	"fmt"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
	"golang.zx2c4.com/wintun"
)

const description = "Подключает компьютер к Kirov VPN, хранит ключи серверов и не даёт DNS уходить мимо VPN. Без этой службы Kirov VPN не работает."

// Install registers the service to run exe at boot as LocalSystem, or
// updates the registration, and starts it.
func Install(exe string) error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	cfg := mgr.Config{
		ServiceType:  windows.SERVICE_WIN32_OWN_PROCESS,
		StartType:    mgr.StartAutomatic,
		ErrorControl: mgr.ErrorNormal,
		DisplayName:  Display,
		Description:  description,
		// WFP filters need the Base Filtering Engine.
		Dependencies: []string{"Nsi", "TcpIp", "BFE"},
		SidType:      windows.SERVICE_SID_TYPE_UNRESTRICTED,
	}
	s, err := m.OpenService(Name)
	if err == nil {
		// An update: the installer has stopped the old one.
		cfg.BinaryPathName = exe
		cfg.ServiceStartName = "LocalSystem"
		err = s.UpdateConfig(cfg)
	} else {
		s, err = m.CreateService(Name, exe, cfg)
	}
	if err != nil {
		return err
	}
	defer s.Close()
	// Restarts after a crash, and after a start that failed.
	if err := s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 2 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 10 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 60 * time.Second},
	}, uint32((24 * time.Hour).Seconds())); err != nil {
		return err
	}
	if err := s.SetRecoveryActionsOnNonCrashFailures(true); err != nil {
		return err
	}
	if err := s.Start(); err != nil && !errors.Is(err, windows.ERROR_SERVICE_ALREADY_RUNNING) {
		return err
	}
	return nil
}

// Stop stops the service, if it runs, and waits until it has.
func Stop() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(Name)
	if errors.Is(err, windows.ERROR_SERVICE_DOES_NOT_EXIST) {
		return nil
	}
	if err != nil {
		return err
	}
	defer s.Close()
	return stopAndWait(s)
}

// Uninstall stops and removes the service, and the wintun driver if no
// other program uses it.
func Uninstall() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(Name)
	if err == nil {
		err = errors.Join(stopAndWait(s), s.Delete())
		s.Close()
		if err != nil {
			return err
		}
	} else if !errors.Is(err, windows.ERROR_SERVICE_DOES_NOT_EXIST) {
		return err
	}
	// Fails while another program's adapter uses the driver: it stays then.
	_ = wintun.Uninstall()
	return nil
}

func stopAndWait(s *mgr.Service) error {
	status, err := s.Control(svc.Stop)
	if errors.Is(err, windows.ERROR_SERVICE_NOT_ACTIVE) {
		return nil
	}
	if err != nil {
		return err
	}
	deadline := time.Now().Add(stopWait + 15*time.Second)
	for status.State != svc.Stopped {
		if time.Now().After(deadline) {
			return fmt.Errorf("служба %s не остановилась", Name)
		}
		time.Sleep(300 * time.Millisecond)
		if status, err = s.Query(); err != nil {
			return err
		}
	}
	return nil
}
