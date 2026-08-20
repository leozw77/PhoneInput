//go:build windows

package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"
)

const (
	className   = "PhoneInputEnhancedImageTrayWindow"
	windowTitle = "PhoneInputEnhanced 图片中转"
	maxImages   = 5

	wmDestroy     = 0x0002
	wmPaint       = 0x000F
	wmClose       = 0x0010
	wmCopyData    = 0x004A
	wmLButtonDown = 0x0201
	wmLButtonUp   = 0x0202
	wmMouseMove   = 0x0200

	mkLButton = 0x0001

	wsPopup        = 0x80000000
	wsBorder       = 0x00800000
	wsExTopmost    = 0x00000008
	wsExToolWindow = 0x00000080

	swHide           = 0
	swShowNoActivate = 4

	swpNoActivate = 0x0010
	hwndTopmost   = ^uintptr(0)

	csHRedraw = 0x0002
	csVRedraw = 0x0001

	spiGetWorkArea = 0x0030

	dtSingleLine  = 0x0020
	dtEndEllipsis = 0x8000
	dtVCenter     = 0x0004
	dtCenter      = 0x0001
	transparent   = 1

	psSolid = 0

	cfHDrop         = 15
	tymedHGlobal    = 1
	dvaspectContent = 1
	datadirGet      = 1

	gmemMoveable = 0x0002
	gmemZeroInit = 0x0040

	dropEffectCopy             = 1
	dragDropSCancel            = 0x00040101
	dragDropSDrop              = 0x00040100
	dragDropSUseDefaultCursors = 0x00040102

	sOK                = 0
	sFalse             = 1
	eNoInterface       = 0x80004002
	eInvalidArg        = 0x80070057
	eNotImpl           = 0x80004001
	dvEFormatEtc       = 0x80040064
	dataSSameFormatEtc = 0x00040130
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	gdi32    = syscall.NewLazyDLL("gdi32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	ole32    = syscall.NewLazyDLL("ole32.dll")
	gdiplus  = syscall.NewLazyDLL("gdiplus.dll")

	procRegisterClassExW      = user32.NewProc("RegisterClassExW")
	procCreateWindowExW       = user32.NewProc("CreateWindowExW")
	procDefWindowProcW        = user32.NewProc("DefWindowProcW")
	procShowWindow            = user32.NewProc("ShowWindow")
	procUpdateWindow          = user32.NewProc("UpdateWindow")
	procGetMessageW           = user32.NewProc("GetMessageW")
	procTranslateMessage      = user32.NewProc("TranslateMessage")
	procDispatchMessageW      = user32.NewProc("DispatchMessageW")
	procPostQuitMessage       = user32.NewProc("PostQuitMessage")
	procBeginPaint            = user32.NewProc("BeginPaint")
	procEndPaint              = user32.NewProc("EndPaint")
	procInvalidateRect        = user32.NewProc("InvalidateRect")
	procFindWindowW           = user32.NewProc("FindWindowW")
	procSendMessageW          = user32.NewProc("SendMessageW")
	procSetWindowPos          = user32.NewProc("SetWindowPos")
	procSystemParametersInfoW = user32.NewProc("SystemParametersInfoW")
	procLoadCursorW           = user32.NewProc("LoadCursorW")
	procSetCapture            = user32.NewProc("SetCapture")
	procReleaseCapture        = user32.NewProc("ReleaseCapture")
	procSetProcessDPIAware    = user32.NewProc("SetProcessDPIAware")
	procDrawTextW             = user32.NewProc("DrawTextW")
	procSetBkMode             = gdi32.NewProc("SetBkMode")
	procSetTextColor          = gdi32.NewProc("SetTextColor")
	procCreateSolidBrush      = gdi32.NewProc("CreateSolidBrush")
	procFillRect              = user32.NewProc("FillRect")
	procFrameRect             = user32.NewProc("FrameRect")
	procDeleteObject          = gdi32.NewProc("DeleteObject")
	procCreatePen             = gdi32.NewProc("CreatePen")
	procSelectObject          = gdi32.NewProc("SelectObject")
	procMoveToEx              = gdi32.NewProc("MoveToEx")
	procLineTo                = gdi32.NewProc("LineTo")
	procShellExecuteW         = shell32.NewProc("ShellExecuteW")
	procGetModuleHandleW      = kernel32.NewProc("GetModuleHandleW")
	procGlobalAlloc           = kernel32.NewProc("GlobalAlloc")
	procGlobalLock            = kernel32.NewProc("GlobalLock")
	procGlobalUnlock          = kernel32.NewProc("GlobalUnlock")
	procGlobalFree            = kernel32.NewProc("GlobalFree")
	procOleInitialize         = ole32.NewProc("OleInitialize")
	procOleUninitialize       = ole32.NewProc("OleUninitialize")
	procDoDragDrop            = ole32.NewProc("DoDragDrop")

	procGdiplusStartup        = gdiplus.NewProc("GdiplusStartup")
	procGdiplusShutdown       = gdiplus.NewProc("GdiplusShutdown")
	procGdipLoadImageFromFile = gdiplus.NewProc("GdipLoadImageFromFile")
	procGdipDisposeImage      = gdiplus.NewProc("GdipDisposeImage")
	procGdipGetImageWidth     = gdiplus.NewProc("GdipGetImageWidth")
	procGdipGetImageHeight    = gdiplus.NewProc("GdipGetImageHeight")
	procGdipCreateFromHDC     = gdiplus.NewProc("GdipCreateFromHDC")
	procGdipDeleteGraphics    = gdiplus.NewProc("GdipDeleteGraphics")
	procGdipDrawImageRectI    = gdiplus.NewProc("GdipDrawImageRectI")
)

type point struct{ X, Y int32 }
type rect struct{ Left, Top, Right, Bottom int32 }
type msg struct {
	HWnd           uintptr
	Message        uint32
	WParam, LParam uintptr
	Time           uint32
	Pt             point
	LPrivate       uint32
}
type paintStruct struct {
	HDC         uintptr
	FErase      int32
	RcPaint     rect
	FRestore    int32
	FIncUpdate  int32
	RGBReserved [32]byte
}
type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     uintptr
	HIcon         uintptr
	HCursor       uintptr
	HbrBackground uintptr
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       uintptr
}
type copyDataStruct struct {
	DwData uintptr
	CbData uint32
	_      uint32
	LpData uintptr
}
type gdiplusStartupInput struct {
	GdiplusVersion           uint32
	DebugEventCallback       uintptr
	SuppressBackgroundThread int32
	SuppressExternalCodecs   int32
}

type imageItem struct {
	path          string
	image         uintptr
	width, height uint32
}

var app struct {
	hwnd         uintptr
	gdipToken    uintptr
	images       []imageItem
	dragIndex    int
	downX, downY int32
	dragging     bool
}

func main() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	_, _, _ = procSetProcessDPIAware.Call()

	paths := validImageArgs(os.Args[1:])
	if sendToExisting(paths) {
		return
	}

	hr, _, _ := procOleInitialize.Call(0)
	if uint32(hr) != 0 && uint32(hr) != 1 {
		return
	}
	defer procOleUninitialize.Call()
	if !startGDIPlus() {
		return
	}
	defer stopGDIPlus()

	app.dragIndex = -1
	for i := len(paths) - 1; i >= 0; i-- {
		addImage(paths[i])
	}
	if !createMainWindow() {
		return
	}
	positionAndShow()

	var m msg
	for {
		r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
	disposeImages()
}

func validImageArgs(args []string) []string {
	out := make([]string, 0, len(args))
	for _, raw := range args {
		p, err := filepath.Abs(strings.TrimSpace(raw))
		if err != nil {
			continue
		}
		info, err := os.Stat(p)
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		switch strings.ToLower(filepath.Ext(p)) {
		case ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif":
			out = append(out, p)
		}
	}
	return out
}

func sendToExisting(paths []string) bool {
	cls, _ := syscall.UTF16PtrFromString(className)
	hwnd, _, _ := procFindWindowW.Call(uintptr(unsafe.Pointer(cls)), 0)
	if hwnd == 0 {
		return false
	}
	for _, p := range paths {
		sendCopyData(hwnd, p)
	}
	procShowWindow.Call(hwnd, swShowNoActivate)
	return true
}

func sendCopyData(hwnd uintptr, text string) {
	u16, _ := syscall.UTF16FromString(text)
	cds := copyDataStruct{CbData: uint32(len(u16) * 2), LpData: uintptr(unsafe.Pointer(&u16[0]))}
	procSendMessageW.Call(hwnd, wmCopyData, 0, uintptr(unsafe.Pointer(&cds)))
	runtime.KeepAlive(u16)
}

func startGDIPlus() bool {
	input := gdiplusStartupInput{GdiplusVersion: 1}
	status, _, _ := procGdiplusStartup.Call(uintptr(unsafe.Pointer(&app.gdipToken)), uintptr(unsafe.Pointer(&input)), 0)
	return status == 0 && app.gdipToken != 0
}
func stopGDIPlus() {
	if app.gdipToken != 0 {
		procGdiplusShutdown.Call(app.gdipToken)
		app.gdipToken = 0
	}
}

func createMainWindow() bool {
	hinst, _, _ := procGetModuleHandleW.Call(0)
	cls, _ := syscall.UTF16PtrFromString(className)
	title, _ := syscall.UTF16PtrFromString(windowTitle)
	cursor, _, _ := procLoadCursorW.Call(0, 32512)
	bg, _, _ := procCreateSolidBrush.Call(rgb(247, 247, 247))
	wc := wndClassEx{CbSize: uint32(unsafe.Sizeof(wndClassEx{})), Style: csHRedraw | csVRedraw, LpfnWndProc: syscall.NewCallback(wndProc), HInstance: hinst, HCursor: cursor, HbrBackground: bg, LpszClassName: cls}
	atom, _, _ := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	if atom == 0 {
		return false
	}
	hwnd, _, _ := procCreateWindowExW.Call(wsExTopmost|wsExToolWindow, uintptr(unsafe.Pointer(cls)), uintptr(unsafe.Pointer(title)), wsPopup|wsBorder, 0, 0, 660, 180, 0, 0, hinst, 0)
	if hwnd == 0 {
		return false
	}
	app.hwnd = hwnd
	return true
}

func positionAndShow() {
	if app.hwnd == 0 {
		return
	}
	var work rect
	procSystemParametersInfoW.Call(spiGetWorkArea, 0, uintptr(unsafe.Pointer(&work)), 0)
	const w, h int32 = 660, 180
	x := work.Left + (work.Right-work.Left-w)/2
	y := work.Bottom - h - 10
	procSetWindowPos.Call(app.hwnd, hwndTopmost, uintptr(x), uintptr(y), uintptr(w), uintptr(h), swpNoActivate)
	procShowWindow.Call(app.hwnd, swShowNoActivate)
	procUpdateWindow.Call(app.hwnd)
	procInvalidateRect.Call(app.hwnd, 0, 1)
}

func wndProc(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
	switch message {
	case wmCopyData:
		cds := (*copyDataStruct)(unsafe.Pointer(lParam))
		if cds != nil && cds.LpData != 0 && cds.CbData >= 2 {
			count := int(cds.CbData / 2)
			chars := unsafe.Slice((*uint16)(unsafe.Pointer(cds.LpData)), count)
			text := syscall.UTF16ToString(chars)
			if p := validImageArgs([]string{text}); len(p) == 1 {
				addImage(p[0])
				positionAndShow()
			}
		}
		return 1
	case wmPaint:
		paint(hwnd)
		return 0
	case wmLButtonDown:
		x, y := mouseXY(lParam)
		if closeHit(x, y) {
			procShowWindow.Call(hwnd, swHide)
			return 0
		}
		idx := thumbnailHit(x, y)
		if idx >= 0 {
			app.dragIndex = idx
			app.downX = x
			app.downY = y
			app.dragging = false
			procSetCapture.Call(hwnd)
		}
		return 0
	case wmMouseMove:
		if app.dragIndex >= 0 && (wParam&mkLButton) != 0 && app.dragIndex < len(app.images) {
			x, y := mouseXY(lParam)
			if abs32(x-app.downX) > 5 || abs32(y-app.downY) > 5 {
				idx := app.dragIndex
				app.dragIndex = -1
				app.dragging = true
				procReleaseCapture.Call()
				path := app.images[idx].path
				doFileDrag(path)
				app.dragging = false
			}
		}
		return 0
	case wmLButtonUp:
		x, y := mouseXY(lParam)
		idx := app.dragIndex
		app.dragIndex = -1
		procReleaseCapture.Call()
		if idx >= 0 && !app.dragging && idx == thumbnailHit(x, y) && idx < len(app.images) {
			openFile(app.images[idx].path)
		}
		app.dragging = false
		return 0
	case wmClose:
		procShowWindow.Call(hwnd, swHide)
		return 0
	case wmDestroy:
		procPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := procDefWindowProcW.Call(hwnd, uintptr(message), wParam, lParam)
	return r
}

func paint(hwnd uintptr) {
	var ps paintStruct
	hdc, _, _ := procBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	if hdc == 0 {
		return
	}
	defer procEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))

	bg, _, _ := procCreateSolidBrush.Call(rgb(247, 247, 247))
	defer procDeleteObject.Call(bg)
	full := rect{0, 0, 660, 180}
	procFillRect.Call(hdc, uintptr(unsafe.Pointer(&full)), bg)
	procSetBkMode.Call(hdc, transparent)
	procSetTextColor.Call(hdc, rgb(32, 32, 32))
	drawText(hdc, "手机图片中转 · 按住缩略图拖到 ChatGPT / 浏览器", rect{14, 8, 600, 32}, 0)
	procSetTextColor.Call(hdc, rgb(100, 100, 100))
	drawText(hdc, "×", rect{620, 4, 652, 32}, dtCenter|dtVCenter|dtSingleLine)

	border, _, _ := procCreateSolidBrush.Call(rgb(215, 215, 215))
	defer procDeleteObject.Call(border)
	for i, item := range app.images {
		r := thumbRect(i)
		procFrameRect.Call(hdc, uintptr(unsafe.Pointer(&r)), border)
		drawImageFit(hdc, item, r.Left+3, r.Top+3, r.Right-r.Left-6, r.Bottom-r.Top-6)
		name := filepath.Base(item.path)
		procSetTextColor.Call(hdc, rgb(70, 70, 70))
		drawText(hdc, name, rect{r.Left, r.Bottom + 4, r.Right, r.Bottom + 24}, dtSingleLine|dtEndEllipsis|dtCenter|dtVCenter)
	}
	if len(app.images) == 0 {
		procSetTextColor.Call(hdc, rgb(120, 120, 120))
		drawText(hdc, "收到手机截图后会自动显示在这里", rect{20, 70, 640, 110}, dtCenter|dtVCenter|dtSingleLine)
	}
}

func drawText(hdc uintptr, text string, r rect, flags uint32) {
	p, _ := syscall.UTF16PtrFromString(text)
	procDrawTextW.Call(hdc, uintptr(unsafe.Pointer(p)), ^uintptr(0), uintptr(unsafe.Pointer(&r)), uintptr(flags))
}

func drawImageFit(hdc uintptr, item imageItem, x, y, w, h int32) {
	if item.image == 0 || item.width == 0 || item.height == 0 {
		return
	}
	var g uintptr
	status, _, _ := procGdipCreateFromHDC.Call(hdc, uintptr(unsafe.Pointer(&g)))
	if status != 0 || g == 0 {
		return
	}
	defer procGdipDeleteGraphics.Call(g)
	iw, ih := int64(item.width), int64(item.height)
	dw, dh := int64(w), int64(h)
	if iw*dh > ih*dw {
		dh = ih * dw / iw
	} else {
		dw = iw * dh / ih
	}
	dx := int64(x) + (int64(w)-dw)/2
	dy := int64(y) + (int64(h)-dh)/2
	procGdipDrawImageRectI.Call(g, item.image, uintptr(dx), uintptr(dy), uintptr(dw), uintptr(dh))
}

func addImage(path string) {
	path, _ = filepath.Abs(path)
	var image uintptr
	p, _ := syscall.UTF16PtrFromString(path)
	status, _, _ := procGdipLoadImageFromFile.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&image)))
	if status != 0 || image == 0 {
		return
	}
	var width, height uint32
	if st, _, _ := procGdipGetImageWidth.Call(image, uintptr(unsafe.Pointer(&width))); st != 0 {
		procGdipDisposeImage.Call(image)
		return
	}
	if st, _, _ := procGdipGetImageHeight.Call(image, uintptr(unsafe.Pointer(&height))); st != 0 {
		procGdipDisposeImage.Call(image)
		return
	}
	filtered := make([]imageItem, 0, maxImages)
	for _, old := range app.images {
		if strings.EqualFold(old.path, path) {
			procGdipDisposeImage.Call(old.image)
			continue
		}
		filtered = append(filtered, old)
	}
	app.images = append([]imageItem{{path: path, image: image, width: width, height: height}}, filtered...)
	if len(app.images) > maxImages {
		for _, old := range app.images[maxImages:] {
			procGdipDisposeImage.Call(old.image)
		}
		app.images = app.images[:maxImages]
	}
	if app.hwnd != 0 {
		procInvalidateRect.Call(app.hwnd, 0, 1)
	}
}
func disposeImages() {
	for _, item := range app.images {
		if item.image != 0 {
			procGdipDisposeImage.Call(item.image)
		}
	}
	app.images = nil
}

func thumbRect(i int) rect { left := int32(14 + i*126); return rect{left, 38, left + 112, 140} }
func thumbnailHit(x, y int32) int {
	for i := range app.images {
		r := thumbRect(i)
		if x >= r.Left && x < r.Right && y >= r.Top && y < r.Bottom {
			return i
		}
	}
	return -1
}
func closeHit(x, y int32) bool { return x >= 612 && x < 660 && y >= 0 && y < 36 }
func mouseXY(l uintptr) (int32, int32) {
	return int32(int16(l & 0xffff)), int32(int16((l >> 16) & 0xffff))
}
func abs32(v int32) int32 {
	if v < 0 {
		return -v
	}
	return v
}
func rgb(r, g, b byte) uintptr { return uintptr(r) | uintptr(g)<<8 | uintptr(b)<<16 }

func openFile(path string) {
	verb, _ := syscall.UTF16PtrFromString("open")
	p, _ := syscall.UTF16PtrFromString(path)
	procShellExecuteW.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(p)), 0, 0, 1)
}

// --- OLE drag source: expose one real file as CF_HDROP, so browsers and ChatGPT
// receive the exact same payload as a drag from Windows Explorer. ---

type guid struct {
	Data1        uint32
	Data2, Data3 uint16
	Data4        [8]byte
}

var iidIUnknown = guid{0x00000000, 0x0000, 0x0000, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
var iidIDataObject = guid{0x0000010e, 0x0000, 0x0000, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
var iidIDropSource = guid{0x00000121, 0x0000, 0x0000, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}
var iidIEnumFORMATETC = guid{0x00000103, 0x0000, 0x0000, [8]byte{0xC0, 0, 0, 0, 0, 0, 0, 0x46}}

func equalGUID(a, b *guid) bool { return a != nil && b != nil && *a == *b }

type formatEtc struct {
	CfFormat uint16
	_        [6]byte
	Ptd      uintptr
	DwAspect uint32
	Lindex   int32
	Tymed    uint32
	_2       uint32
}
type stgMedium struct {
	Tymed          uint32
	_              uint32
	Value          uintptr
	PUnkForRelease uintptr
}
type dropFiles struct {
	PFiles   uint32
	PtX, PtY int32
	FNC      int32
	FWide    int32
}

type dataObjectVTable struct{ QueryInterface, AddRef, Release, GetData, GetDataHere, QueryGetData, GetCanonicalFormatEtc, SetData, EnumFormatEtc, DAdvise, DUnadvise, EnumDAdvise uintptr }
type dataObject struct {
	VTable *dataObjectVTable
	Ref    int32
	Path   string
}
type dropSourceVTable struct{ QueryInterface, AddRef, Release, QueryContinueDrag, GiveFeedback uintptr }
type dropSource struct {
	VTable *dropSourceVTable
	Ref    int32
}

type enumFormatVTable struct {
	QueryInterface, AddRef, Release, Next, Skip, Reset, Clone uintptr
}

type enumFormat struct {
	VTable *enumFormatVTable
	Ref    int32
	Index  uint32
}

var enumRegistry = struct {
	sync.Mutex
	items map[uintptr]*enumFormat
}{items: map[uintptr]*enumFormat{}}

var dataVTable = dataObjectVTable{
	syscall.NewCallback(dataQueryInterface), syscall.NewCallback(dataAddRef), syscall.NewCallback(dataRelease), syscall.NewCallback(dataGetData), syscall.NewCallback(dataGetDataHere), syscall.NewCallback(dataQueryGetData), syscall.NewCallback(dataGetCanonicalFormatEtc), syscall.NewCallback(dataSetData), syscall.NewCallback(dataEnumFormatEtc), syscall.NewCallback(dataDAdvise), syscall.NewCallback(dataDUnadvise), syscall.NewCallback(dataEnumDAdvise),
}
var sourceVTable = dropSourceVTable{
	syscall.NewCallback(sourceQueryInterface), syscall.NewCallback(sourceAddRef), syscall.NewCallback(sourceRelease), syscall.NewCallback(sourceQueryContinueDrag), syscall.NewCallback(sourceGiveFeedback),
}
var enumVTable = enumFormatVTable{
	syscall.NewCallback(enumQueryInterface), syscall.NewCallback(enumAddRef), syscall.NewCallback(enumRelease), syscall.NewCallback(enumNext), syscall.NewCallback(enumSkip), syscall.NewCallback(enumReset), syscall.NewCallback(enumClone),
}

func doFileDrag(path string) {
	d := &dataObject{VTable: &dataVTable, Ref: 1, Path: path}
	s := &dropSource{VTable: &sourceVTable, Ref: 1}
	var effect uint32
	procDoDragDrop.Call(uintptr(unsafe.Pointer(d)), uintptr(unsafe.Pointer(s)), dropEffectCopy, uintptr(unsafe.Pointer(&effect)))
	runtime.KeepAlive(d)
	runtime.KeepAlive(s)
}

func dataQueryInterface(this, riid, ppv uintptr) uintptr {
	if ppv == 0 {
		return eNoInterface
	}
	*(*uintptr)(unsafe.Pointer(ppv)) = 0
	id := (*guid)(unsafe.Pointer(riid))
	if equalGUID(id, &iidIUnknown) || equalGUID(id, &iidIDataObject) {
		*(*uintptr)(unsafe.Pointer(ppv)) = this
		dataAddRef(this)
		return sOK
	}
	return eNoInterface
}
func dataAddRef(this uintptr) uintptr {
	d := (*dataObject)(unsafe.Pointer(this))
	return uintptr(atomic.AddInt32(&d.Ref, 1))
}
func dataRelease(this uintptr) uintptr {
	d := (*dataObject)(unsafe.Pointer(this))
	n := atomic.AddInt32(&d.Ref, -1)
	return uintptr(n)
}
func dataGetData(this, pfmt, pmed uintptr) uintptr {
	if dataQueryGetData(this, pfmt) != sOK || pmed == 0 {
		return dvEFormatEtc
	}
	d := (*dataObject)(unsafe.Pointer(this))
	h := makeHDrop(d.Path)
	if h == 0 {
		return eNotImpl
	}
	m := (*stgMedium)(unsafe.Pointer(pmed))
	*m = stgMedium{Tymed: tymedHGlobal, Value: h}
	return sOK
}
func dataGetDataHere(this, pfmt, pmed uintptr) uintptr { return eNotImpl }
func dataQueryGetData(this, pfmt uintptr) uintptr {
	if pfmt == 0 {
		return dvEFormatEtc
	}
	f := (*formatEtc)(unsafe.Pointer(pfmt))
	if f.CfFormat == cfHDrop && f.DwAspect == dvaspectContent && (f.Tymed&tymedHGlobal) != 0 {
		return sOK
	}
	return dvEFormatEtc
}
func dataGetCanonicalFormatEtc(this, pfmtIn, pfmtOut uintptr) uintptr {
	if pfmtOut != 0 {
		out := (*formatEtc)(unsafe.Pointer(pfmtOut))
		out.Ptd = 0
	}
	return dataSSameFormatEtc
}
func dataSetData(this, pfmt, pmed, release uintptr) uintptr { return eNotImpl }
func dataEnumFormatEtc(this, direction, enumOut uintptr) uintptr {
	if direction != datadirGet || enumOut == 0 {
		return eNotImpl
	}
	e := &enumFormat{VTable: &enumVTable, Ref: 1}
	ptr := uintptr(unsafe.Pointer(e))
	enumRegistry.Lock()
	enumRegistry.items[ptr] = e
	enumRegistry.Unlock()
	*(*uintptr)(unsafe.Pointer(enumOut)) = ptr
	return sOK
}
func dataDAdvise(this, pfmt, advf, sink, conn uintptr) uintptr { return eNotImpl }
func dataDUnadvise(this, conn uintptr) uintptr                 { return eNotImpl }
func dataEnumDAdvise(this, enumOut uintptr) uintptr            { return eNotImpl }

func enumQueryInterface(this, riid, ppv uintptr) uintptr {
	if ppv == 0 || riid == 0 {
		return eNoInterface
	}
	*(*uintptr)(unsafe.Pointer(ppv)) = 0
	id := (*guid)(unsafe.Pointer(riid))
	if equalGUID(id, &iidIUnknown) || equalGUID(id, &iidIEnumFORMATETC) {
		*(*uintptr)(unsafe.Pointer(ppv)) = this
		enumAddRef(this)
		return sOK
	}
	return eNoInterface
}

func enumAddRef(this uintptr) uintptr {
	e := (*enumFormat)(unsafe.Pointer(this))
	return uintptr(atomic.AddInt32(&e.Ref, 1))
}

func enumRelease(this uintptr) uintptr {
	e := (*enumFormat)(unsafe.Pointer(this))
	n := atomic.AddInt32(&e.Ref, -1)
	if n == 0 {
		enumRegistry.Lock()
		delete(enumRegistry.items, this)
		enumRegistry.Unlock()
	}
	return uintptr(n)
}

func enumNext(this, celt, rgelt, fetched uintptr) uintptr {
	if celt == 0 || rgelt == 0 || (celt != 1 && fetched == 0) {
		return eInvalidArg
	}
	e := (*enumFormat)(unsafe.Pointer(this))
	if fetched != 0 {
		*(*uint32)(unsafe.Pointer(fetched)) = 0
	}
	if e.Index > 0 {
		return sFalse
	}
	f := (*formatEtc)(unsafe.Pointer(rgelt))
	*f = formatEtc{CfFormat: cfHDrop, DwAspect: dvaspectContent, Lindex: -1, Tymed: tymedHGlobal}
	e.Index = 1
	if fetched != 0 {
		*(*uint32)(unsafe.Pointer(fetched)) = 1
	}
	if celt == 1 {
		return sOK
	}
	return sFalse
}

func enumSkip(this, celt uintptr) uintptr {
	e := (*enumFormat)(unsafe.Pointer(this))
	if celt == 0 {
		return sOK
	}
	if e.Index == 0 {
		e.Index = 1
		if celt == 1 {
			return sOK
		}
	}
	return sFalse
}

func enumReset(this uintptr) uintptr {
	e := (*enumFormat)(unsafe.Pointer(this))
	e.Index = 0
	return sOK
}

func enumClone(this, out uintptr) uintptr {
	if out == 0 {
		return eInvalidArg
	}
	e := (*enumFormat)(unsafe.Pointer(this))
	clone := &enumFormat{VTable: e.VTable, Ref: 1, Index: e.Index}
	ptr := uintptr(unsafe.Pointer(clone))
	enumRegistry.Lock()
	enumRegistry.items[ptr] = clone
	enumRegistry.Unlock()
	*(*uintptr)(unsafe.Pointer(out)) = ptr
	return sOK
}

func sourceQueryInterface(this, riid, ppv uintptr) uintptr {
	if ppv == 0 {
		return eNoInterface
	}
	*(*uintptr)(unsafe.Pointer(ppv)) = 0
	id := (*guid)(unsafe.Pointer(riid))
	if equalGUID(id, &iidIUnknown) || equalGUID(id, &iidIDropSource) {
		*(*uintptr)(unsafe.Pointer(ppv)) = this
		sourceAddRef(this)
		return sOK
	}
	return eNoInterface
}
func sourceAddRef(this uintptr) uintptr {
	s := (*dropSource)(unsafe.Pointer(this))
	return uintptr(atomic.AddInt32(&s.Ref, 1))
}
func sourceRelease(this uintptr) uintptr {
	s := (*dropSource)(unsafe.Pointer(this))
	n := atomic.AddInt32(&s.Ref, -1)
	return uintptr(n)
}
func sourceQueryContinueDrag(this, escape, keyState uintptr) uintptr {
	if escape != 0 {
		return dragDropSCancel
	}
	if (keyState & mkLButton) == 0 {
		return dragDropSDrop
	}
	return sOK
}
func sourceGiveFeedback(this, effect uintptr) uintptr { return dragDropSUseDefaultCursors }

func makeHDrop(path string) uintptr {
	u16, err := syscall.UTF16FromString(path)
	if err != nil {
		return 0
	}
	u16 = append(u16, 0)
	size := int(unsafe.Sizeof(dropFiles{})) + len(u16)*2
	h, _, _ := procGlobalAlloc.Call(gmemMoveable|gmemZeroInit, uintptr(size))
	if h == 0 {
		return 0
	}
	mem, _, _ := procGlobalLock.Call(h)
	if mem == 0 {
		procGlobalFree.Call(h)
		return 0
	}
	header := (*dropFiles)(unsafe.Pointer(mem))
	*header = dropFiles{PFiles: uint32(unsafe.Sizeof(dropFiles{})), FWide: 1}
	dst := unsafe.Slice((*uint16)(unsafe.Pointer(mem+unsafe.Sizeof(dropFiles{}))), len(u16))
	copy(dst, u16)
	procGlobalUnlock.Call(h)
	runtime.KeepAlive(u16)
	return h
}
