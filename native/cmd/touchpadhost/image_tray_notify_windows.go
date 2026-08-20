//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

func (s *server) notifyImageTray(path string) {
	exe, err := os.Executable()
	if err != nil {
		s.logger.Printf("Image tray notify skipped; executable path unavailable: %v", err)
		return
	}
	helper := filepath.Join(filepath.Dir(exe), "PhoneInputImageTray.exe")
	if info, err := os.Stat(helper); err != nil || info.IsDir() {
		s.logger.Printf("Image tray helper missing; Path=%s", helper)
		return
	}
	command := exec.Command(helper, path)
	command.Dir = filepath.Dir(helper)
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	if err := command.Start(); err != nil {
		s.logger.Printf("Image tray notify failed; Reason=%v", err)
		return
	}
	go func() { _ = command.Wait() }()
}
