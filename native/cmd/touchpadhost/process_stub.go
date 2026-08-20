//go:build !windows

package main

import "os/exec"

func combinedOutputHidden(name string, args ...string) ([]byte, error) {
	return exec.Command(name, args...).CombinedOutput()
}
