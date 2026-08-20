//go:build windows

package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"image"
	"image/png"
	"path/filepath"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

const (
	cfUnicodeText                  = 13
	gmemMoveable                   = 0x0002
	processQueryLimitedInformation = 0x1000
	smXVirtualScreen               = 76
	smYVirtualScreen               = 77
	smCXVirtualScreen              = 78
	smCYVirtualScreen              = 79
	srccopy                        = 0x00CC0020
	captureblt                     = 0x40000000
	dibRGBColors                   = 0
	biRGB                          = 0
)

var (
	gdi32           = syscall.NewLazyDLL("gdi32.dll")
	kernel32Desktop = syscall.NewLazyDLL("kernel32.dll")

	procOpenClipboard              = user32.NewProc("OpenClipboard")
	procCloseClipboard             = user32.NewProc("CloseClipboard")
	procGetClipboardData           = user32.NewProc("GetClipboardData")
	procSetClipboardData           = user32.NewProc("SetClipboardData")
	procEmptyClipboard             = user32.NewProc("EmptyClipboard")
	procGetClipboardSequenceNumber = user32.NewProc("GetClipboardSequenceNumber")
	procIsClipboardFormatAvailable = user32.NewProc("IsClipboardFormatAvailable")
	procGetForegroundWindow        = user32.NewProc("GetForegroundWindow")
	procGetWindowTextLengthW       = user32.NewProc("GetWindowTextLengthW")
	procGetWindowTextW             = user32.NewProc("GetWindowTextW")
	procGetWindowThreadProcessId   = user32.NewProc("GetWindowThreadProcessId")
	procGetSystemMetrics           = user32.NewProc("GetSystemMetrics")
	procGetDC                      = user32.NewProc("GetDC")
	procReleaseDC                  = user32.NewProc("ReleaseDC")

	procGlobalAlloc                = kernel32Desktop.NewProc("GlobalAlloc")
	procGlobalLock                 = kernel32Desktop.NewProc("GlobalLock")
	procGlobalUnlock               = kernel32Desktop.NewProc("GlobalUnlock")
	procGlobalFree                 = kernel32Desktop.NewProc("GlobalFree")
	procOpenProcess                = kernel32Desktop.NewProc("OpenProcess")
	procQueryFullProcessImageNameW = kernel32Desktop.NewProc("QueryFullProcessImageNameW")
	procCloseHandle                = kernel32Desktop.NewProc("CloseHandle")

	procCreateCompatibleDC     = gdi32.NewProc("CreateCompatibleDC")
	procDeleteDC               = gdi32.NewProc("DeleteDC")
	procCreateCompatibleBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	procSelectObject           = gdi32.NewProc("SelectObject")
	procDeleteObject           = gdi32.NewProc("DeleteObject")
	procBitBlt                 = gdi32.NewProc("BitBlt")
	procGetDIBits              = gdi32.NewProc("GetDIBits")
)

func openClipboardWithRetry() error {
	for i := 0; i < 8; i++ {
		ret, _, _ := procOpenClipboard.Call(0)
		if ret != 0 {
			return nil
		}
		time.Sleep(12 * time.Millisecond)
	}
	return errors.New("OpenClipboard failed")
}

func clipboardSequence() uint32 {
	ret, _, _ := procGetClipboardSequenceNumber.Call()
	return uint32(ret)
}

func readSystemClipboardText() (string, uint32, bool, error) {
	seq := clipboardSequence()
	available, _, _ := procIsClipboardFormatAvailable.Call(cfUnicodeText)
	if available == 0 {
		return "", seq, false, nil
	}
	if err := openClipboardWithRetry(); err != nil {
		return "", seq, false, err
	}
	defer procCloseClipboard.Call()
	handle, _, _ := procGetClipboardData.Call(cfUnicodeText)
	if handle == 0 {
		return "", clipboardSequence(), false, nil
	}
	ptr, _, _ := procGlobalLock.Call(handle)
	if ptr == 0 {
		return "", clipboardSequence(), false, errors.New("GlobalLock failed")
	}
	defer procGlobalUnlock.Call(handle)
	values := make([]uint16, 0, 256)
	for offset := uintptr(0); offset < 8<<20; offset += 2 {
		value := *(*uint16)(unsafe.Pointer(ptr + offset))
		if value == 0 {
			break
		}
		values = append(values, value)
	}
	return string(utf16.Decode(values)), clipboardSequence(), true, nil
}

func writeSystemClipboardText(text string) error {
	encoded := utf16.Encode([]rune(text))
	encoded = append(encoded, 0)
	size := uintptr(len(encoded) * 2)
	handle, _, callErr := procGlobalAlloc.Call(gmemMoveable, size)
	if handle == 0 {
		return fmt.Errorf("GlobalAlloc failed: %v", callErr)
	}
	owned := true
	defer func() {
		if owned {
			procGlobalFree.Call(handle)
		}
	}()
	ptr, _, _ := procGlobalLock.Call(handle)
	if ptr == 0 {
		return errors.New("GlobalLock failed")
	}
	target := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), len(encoded))
	copy(target, encoded)
	procGlobalUnlock.Call(handle)

	if err := openClipboardWithRetry(); err != nil {
		return err
	}
	defer procCloseClipboard.Call()
	if ret, _, e := procEmptyClipboard.Call(); ret == 0 {
		return fmt.Errorf("EmptyClipboard failed: %v", e)
	}
	if ret, _, e := procSetClipboardData.Call(cfUnicodeText, handle); ret == 0 {
		return fmt.Errorf("SetClipboardData failed: %v", e)
	}
	owned = false
	return nil
}

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type bitmapInfo struct {
	Header bitmapInfoHeader
	Colors [1]uint32
}

func captureDesktopPNG() ([]byte, int, int, error) {
	x, _, _ := procGetSystemMetrics.Call(smXVirtualScreen)
	y, _, _ := procGetSystemMetrics.Call(smYVirtualScreen)
	w, _, _ := procGetSystemMetrics.Call(smCXVirtualScreen)
	h, _, _ := procGetSystemMetrics.Call(smCYVirtualScreen)
	width, height := int(int32(w)), int(int32(h))
	if width <= 0 || height <= 0 || width > 16384 || height > 16384 {
		return nil, 0, 0, errors.New("invalid virtual desktop size")
	}

	screenDC, _, _ := procGetDC.Call(0)
	if screenDC == 0 {
		return nil, 0, 0, errors.New("GetDC failed")
	}
	defer procReleaseDC.Call(0, screenDC)
	memDC, _, _ := procCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		return nil, 0, 0, errors.New("CreateCompatibleDC failed")
	}
	defer procDeleteDC.Call(memDC)
	bitmap, _, _ := procCreateCompatibleBitmap.Call(screenDC, uintptr(width), uintptr(height))
	if bitmap == 0 {
		return nil, 0, 0, errors.New("CreateCompatibleBitmap failed")
	}
	defer procDeleteObject.Call(bitmap)
	old, _, _ := procSelectObject.Call(memDC, bitmap)
	if old == 0 {
		return nil, 0, 0, errors.New("SelectObject failed")
	}
	defer procSelectObject.Call(memDC, old)
	ret, _, callErr := procBitBlt.Call(memDC, 0, 0, uintptr(width), uintptr(height), screenDC, x, y, srccopy|captureblt)
	if ret == 0 {
		return nil, 0, 0, fmt.Errorf("BitBlt failed: %v", callErr)
	}

	stride := width * 4
	pixels := make([]byte, stride*height)
	bmi := bitmapInfo{Header: bitmapInfoHeader{
		Size: uint32(binary.Size(bitmapInfoHeader{})), Width: int32(width), Height: -int32(height),
		Planes: 1, BitCount: 32, Compression: biRGB, SizeImage: uint32(len(pixels)),
	}}
	rows, _, callErr := procGetDIBits.Call(memDC, bitmap, 0, uintptr(height), uintptr(unsafe.Pointer(&pixels[0])), uintptr(unsafe.Pointer(&bmi)), dibRGBColors)
	if int(rows) != height {
		return nil, 0, 0, fmt.Errorf("GetDIBits failed: %v", callErr)
	}

	img := image.NewRGBA(image.Rect(0, 0, width, height))
	for i := 0; i < len(pixels); i += 4 {
		img.Pix[i] = pixels[i+2]
		img.Pix[i+1] = pixels[i+1]
		img.Pix[i+2] = pixels[i]
		img.Pix[i+3] = 255
	}
	var buffer bytes.Buffer
	encoder := png.Encoder{CompressionLevel: png.BestSpeed}
	if err := encoder.Encode(&buffer, img); err != nil {
		return nil, 0, 0, err
	}
	return buffer.Bytes(), width, height, nil
}

func getForegroundWindowInfo() (foregroundWindowInfo, error) {
	hwnd, _, _ := procGetForegroundWindow.Call()
	if hwnd == 0 {
		return foregroundWindowInfo{Target: "other"}, errors.New("no foreground window")
	}
	length, _, _ := procGetWindowTextLengthW.Call(hwnd)
	titleBuf := make([]uint16, int(length)+2)
	procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&titleBuf[0])), uintptr(len(titleBuf)))
	title := syscall.UTF16ToString(titleBuf)
	var pid uint32
	procGetWindowThreadProcessId.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
	process := queryProcessName(pid)
	target := classifyForegroundTarget(process, title)
	return foregroundWindowInfo{Target: target, Title: title, Process: process, PID: pid}, nil
}

func queryProcessName(pid uint32) string {
	handle, _, _ := procOpenProcess.Call(processQueryLimitedInformation, 0, uintptr(pid))
	if handle == 0 {
		return ""
	}
	defer procCloseHandle.Call(handle)
	buffer := make([]uint16, 1024)
	size := uint32(len(buffer))
	ret, _, _ := procQueryFullProcessImageNameW.Call(handle, 0, uintptr(unsafe.Pointer(&buffer[0])), uintptr(unsafe.Pointer(&size)))
	if ret == 0 {
		return ""
	}
	return filepath.Base(syscall.UTF16ToString(buffer[:size]))
}
