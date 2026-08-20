//go:build windows

package main

import (
	"os/exec"
	"syscall"
)

const createNoWindow = 0x08000000

// combinedOutputHidden runs helper console programs without creating a visible
// console window. This is required for reg.exe because the touchpad host is a
// Windows GUI process and periodically checks whether the startup entry still
// points at the legacy core executable.
func combinedOutputHidden(name string, args ...string) ([]byte, error) {
	cmd := exec.Command(name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: createNoWindow,
	}
	return cmd.CombinedOutput()
}
