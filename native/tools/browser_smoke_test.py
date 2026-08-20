#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
HTML_PATH = ROOT / "cmd" / "touchpadhost" / "touchpad.html"
COMPONENT_JS = ROOT / "cmd" / "touchpadhost" / "input_component.js"
COMPONENT_CSS = ROOT / "cmd" / "touchpadhost" / "input_component.css"
CHROMIUM = os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium")

MOCK = r"""
<script>
window.__frames=[];window.__apiRequests=[];window.__wsCount=0;window.__enterFailures=0;
window.__targetType='chatgpt';window.__targetId='chatgpt-id';window.__desktopText='电脑已有文字';
const __store=new Map();
Object.defineProperty(window,'localStorage',{value:{getItem:k=>__store.has(k)?__store.get(k):null,setItem:(k,v)=>__store.set(k,String(v)),removeItem:k=>__store.delete(k),clear:()=>__store.clear()}});
history.pushState=()=>{};history.back=()=>queueMicrotask(()=>window.dispatchEvent(new PopStateEvent('popstate')));
class FakeWebSocket {static OPEN=1;static CLOSED=3;constructor(url){this.url=url;this.readyState=1;window.__wsCount++;queueMicrotask(()=>this.onopen&&this.onopen())}send(data){window.__frames.push(JSON.parse(data))}close(){this.readyState=3;this.onclose&&this.onclose()}}
window.WebSocket=FakeWebSocket;
window.fetch=async(url,options={})=>{
 const raw=String(url), u=new URL(raw,'http://phoneinput.test');
 let body=null;try{body=options.body?JSON.parse(options.body):null}catch{body=options.body||null}
 window.__apiRequests.push({path:u.pathname+u.search,method:options.method||'GET',body});
 if(u.pathname==='/core-api/status') return new Response(JSON.stringify({ok:true,targetId:window.__targetId,targetType:window.__targetType,target:window.__targetType==='wechat'?'微信':window.__targetType==='chrome'?'Chrome':'ChatGPT'}),{status:200,headers:{'Content-Type':'application/json'}});
 if(u.pathname==='/core-api/input-state') return new Response(JSON.stringify({ok:true,supported:true,targetId:u.searchParams.get('targetId')||window.__targetId,targetType:window.__targetType,text:window.__desktopText,selectionStart:window.__desktopText.length,selectionEnd:window.__desktopText.length,controlId:'control-1'}),{status:200,headers:{'Content-Type':'application/json'}});
 if(u.pathname.startsWith('/core-api/window-switch/')){const name=decodeURIComponent(u.pathname.split('/').pop());window.__targetType=name;window.__targetId=name+'-id';return new Response(JSON.stringify({ok:true}),{status:200,headers:{'Content-Type':'application/json'}})}
 if(u.pathname==='/api/key/enter'&&u.searchParams.get('source')==='send-and-enter'&&window.__enterFailures>0){window.__enterFailures--;return new Response(JSON.stringify({error:'mock enter failure'}),{status:500,headers:{'Content-Type':'application/json'}})}
 return new Response(JSON.stringify({ok:true}),{status:200,headers:{'Content-Type':'application/json'}});
};
</script>
"""


def main() -> None:
    html = HTML_PATH.read_text(encoding="utf-8")
    component_js = COMPONENT_JS.read_text(encoding="utf-8").replace("</script", "<\\/script")
    component_css = COMPONENT_CSS.read_text(encoding="utf-8")
    html = html.replace('<link rel="stylesheet" href="/assets/input-component.css">', f"<style>{component_css}</style>", 1)
    html = html.replace('<script src="/assets/input-component.js"></script>', MOCK + f"<script>{component_js}</script>", 1)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=CHROMIUM, args=["--no-sandbox", "--disable-dev-shm-usage"])
        page = browser.new_page(viewport={"width": 390, "height": 780}, has_touch=True, is_mobile=True)
        page.set_content(html, wait_until="domcontentloaded")
        page.wait_for_function("document.querySelector('#state').textContent==='已连接'")
        page.eval_on_selector("#pad", "el=>el.setPointerCapture=()=>{}")
        assert "25000" in html and "type:'ping'" in html and "ensureConnected" in html

        # Default page is touchpad; full original input UI remains a visible switch target.
        legacy = page.locator("#legacyInputButton")
        assert legacy.is_visible()
        assert legacy.get_attribute("href") == "/input/"
        # Top second row shows the three direct window targets; touchpad itself stays clear.
        assert page.locator("#padWindowButton").count() == 0
        assert "任务切换" not in page.locator("body").inner_text()
        pad_targets = page.locator('#topWindows [data-inline-window]')
        assert pad_targets.count() == 3
        assert [pad_targets.nth(i).inner_text() for i in range(3)] == ["ChatGPT", "Chrome", "微信"]
        assert page.locator('#pad [data-inline-window]').count() == 0
        top_box = page.locator("#topWindows").bounding_box()
        pad_box = page.locator("#pad").bounding_box()
        assert top_box and pad_box
        assert top_box["y"] + top_box["height"] <= pad_box["y"]

        shortcut_buttons = page.locator('[data-pad-shortcut]')
        assert shortcut_buttons.count() == 3
        assert [shortcut_buttons.nth(i).inner_text() for i in range(3)] == ["截图", "复制", "粘贴"]
        shortcut_box = page.locator(".shortcuts").bounding_box()
        controls_box = page.locator(".controls").bounding_box()
        assert shortcut_box and controls_box and shortcut_box["y"] >= pad_box["y"] + pad_box["height"]
        assert controls_box["y"] >= shortcut_box["y"] + shortcut_box["height"]

        def dispatch_pointer(kind: str, pointer_id: int, x: int, y: int) -> None:
            page.eval_on_selector(
                "#pad",
                """(el,a)=>el.dispatchEvent(new PointerEvent(a.kind,{pointerId:a.pid,pointerType:'touch',clientX:a.x,clientY:a.y,bubbles:true,cancelable:true,isPrimary:a.pid===1,buttons:a.kind==='pointerup'?0:1}))""",
                {"kind": kind, "pid": pointer_id, "x": x, "y": y},
            )

        def frames(start: int = 0):
            return page.evaluate("n=>window.__frames.slice(n)", start)

        # Single tap remains left click.
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 100, 220)
        dispatch_pointer("pointerup", 1, 100, 220)
        page.wait_for_timeout(20)
        assert any(x.get("type") == "click" and x.get("button") == "left" for x in frames(start))

        # A quick second touch with natural jitter must NOT synthesize held-left drag.
        # This is the browser-tab regression: preview.7 entered drag after only >5 px.
        start = len(frames())
        page.wait_for_timeout(40)
        dispatch_pointer("pointerdown", 1, 101, 221)
        dispatch_pointer("pointermove", 1, 110, 221)
        dispatch_pointer("pointerup", 1, 110, 221)
        page.wait_for_timeout(30)
        output = frames(start)
        assert not any(x.get("type") == "button" and x.get("button") == "left" and x.get("down") is True for x in output), output

        # A true single-finger hold then move performs held-left drag (e.g. WeChat image viewer).
        page.wait_for_timeout(340)
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 120, 230)
        page.wait_for_timeout(245)
        dispatch_pointer("pointermove", 1, 136, 230)
        dispatch_pointer("pointerup", 1, 136, 230)
        page.wait_for_timeout(30)
        output = frames(start)
        assert any(x.get("type") == "button" and x.get("button") == "left" and x.get("down") is True for x in output), output
        assert any(x.get("type") == "button" and x.get("button") == "left" and x.get("down") is False for x in output), output

        # A stationary longer hold is still right-click, not left drag.
        page.wait_for_timeout(340)
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 128, 232)
        page.wait_for_timeout(660)
        dispatch_pointer("pointerup", 1, 128, 232)
        page.wait_for_timeout(30)
        output = frames(start)
        assert any(x.get("type") == "click" and x.get("button") == "right" for x in output), output
        assert not any(x.get("type") == "button" and x.get("button") == "left" and x.get("down") is True for x in output), output

        # Double-tap drag remains available, but only after the second touch is held
        # steadily long enough to arm the drag and then moved clearly.
        page.wait_for_timeout(340)
        dispatch_pointer("pointerdown", 1, 135, 225)
        dispatch_pointer("pointerup", 1, 135, 225)
        page.wait_for_timeout(55)
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 136, 225)
        page.wait_for_timeout(175)
        dispatch_pointer("pointermove", 1, 151, 225)
        dispatch_pointer("pointerup", 1, 151, 225)
        page.wait_for_timeout(30)
        output = frames(start)
        assert any(x.get("type") == "button" and x.get("button") == "left" and x.get("down") is True for x in output), output
        assert any(x.get("type") == "button" and x.get("button") == "left" and x.get("down") is False for x in output), output
        page.wait_for_timeout(340)

        # Two-finger quick tap remains right click.
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 100, 220)
        dispatch_pointer("pointerdown", 2, 160, 220)
        dispatch_pointer("pointerup", 1, 100, 220)
        dispatch_pointer("pointerup", 2, 160, 220)
        page.wait_for_timeout(20)
        output = frames(start)
        assert sum(1 for x in output if x.get("type") == "click" and x.get("button") == "right") == 1, output

        # Two-finger hold opens the in-page DOM input and never emits right click/scroll.
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 105, 250)
        dispatch_pointer("pointerdown", 2, 175, 250)
        page.wait_for_timeout(570)
        page.wait_for_selector("#textOverlay.open")
        assert page.locator("iframe").count() == 0
        dispatch_pointer("pointerup", 1, 105, 250)
        dispatch_pointer("pointerup", 2, 175, 250)
        output = frames(start)
        assert any(x.get("type") == "client_event" and x.get("event") == "two_finger_hold" for x in output), output
        assert not any(x.get("type") in {"click", "scroll", "move"} for x in output), output
        assert page.evaluate("window.__wsCount") == 1

        # Draft survives backdrop-tap closing/reopening because the component stays mounted.
        text = page.locator("#embeddedText")
        text.fill("这是一段还没发送的文字🙂")
        page.locator("#textOverlay").click(position={"x": 4, "y": 4})
        page.wait_for_function("!document.querySelector('#textOverlay').classList.contains('open')")
        page.click("#keyboardButton")
        page.wait_for_selector("#textOverlay.open")
        assert text.input_value() == "这是一段还没发送的文字🙂"

        # Bottom blank dismiss zone is reachable by the thumb and also preserves the draft.
        assert page.locator("#embeddedBottomDismiss").is_visible()
        page.click("#embeddedBottomDismiss")
        page.wait_for_function("!document.querySelector('#textOverlay').classList.contains('open')")
        page.click("#keyboardButton")
        page.wait_for_selector("#textOverlay.open")
        assert text.input_value() == "这是一段还没发送的文字🙂"

        # Once the soft keyboard was observed, viewport recovery auto-closes the embedded panel.
        page.set_viewport_size({"width": 390, "height": 480})
        page.wait_for_timeout(70)
        assert page.locator("#textOverlay").evaluate("el=>el.classList.contains('keyboard-visible')")
        page.set_viewport_size({"width": 390, "height": 780})
        page.wait_for_timeout(310)
        assert not page.locator("#textOverlay").evaluate("el=>el.classList.contains('open')")
        page.click("#keyboardButton")
        page.wait_for_selector("#textOverlay.open")
        assert text.input_value() == "这是一段还没发送的文字🙂"

        # Batch send closes only after confirmed text success.
        text.evaluate("(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}))}", "中文🙂\nline two")
        request_start = page.evaluate("window.__apiRequests.length")
        page.click("#embeddedSend")
        page.wait_for_function("!document.querySelector('#textOverlay').classList.contains('open')")
        requests = page.evaluate("n=>window.__apiRequests.slice(n)", request_start)
        text_requests = [x for x in requests if x["path"] == "/api/text"]
        assert len(text_requests) == 1 and text_requests[0]["body"]["text"] == "中文🙂\nline two", requests

        # Send+Enter: if Enter fails, retry sends only Enter, not text again.
        page.click("#keyboardButton")
        page.wait_for_selector("#textOverlay.open")
        text.evaluate("(el,v)=>{el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}))}", "send-enter-once")
        page.evaluate("window.__enterFailures=1")
        request_start = page.evaluate("window.__apiRequests.length")
        page.click("#embeddedSendEnter")
        page.wait_for_timeout(80)
        assert page.locator("#textOverlay").evaluate("el=>el.classList.contains('open')")
        assert page.locator("#embeddedSendEnter").inner_text() == "重试回车"
        page.click("#embeddedSendEnter")
        page.wait_for_function("!document.querySelector('#textOverlay').classList.contains('open')")
        requests = page.evaluate("n=>window.__apiRequests.slice(n)", request_start)
        assert sum(1 for x in requests if x["path"] == "/api/text") == 1, requests
        assert sum(1 for x in requests if x["path"].startswith("/api/key/enter?source=send-and-enter")) == 2, requests

        # Realtime mode preserves the old input-state/readback and IME composition path.
        page.click("#keyboardButton")
        page.wait_for_selector("#textOverlay.open")
        page.select_option("#embeddedMode", "realtime")
        page.wait_for_timeout(180)
        assert text.input_value() == "电脑已有文字"
        request_start = page.evaluate("window.__apiRequests.length")
        text.evaluate("el=>el.dispatchEvent(new CompositionEvent('compositionstart',{data:'你',bubbles:true}))")
        text.evaluate("el=>{el.value+='你';el.dispatchEvent(new CompositionEvent('compositionend',{data:'你',bubbles:true}))}")
        page.wait_for_timeout(80)
        requests = page.evaluate("n=>window.__apiRequests.slice(n)", request_start)
        realtime_requests = [x for x in requests if x["path"] == "/core-api/text"]
        assert realtime_requests and realtime_requests[-1]["body"]["text"] == "你" and realtime_requests[-1]["body"]["targetId"] == "chatgpt-id", requests
        page.click("#embeddedSyncDesktop")
        page.wait_for_timeout(80)
        assert any(x["path"].startswith("/core-api/input-state?") for x in page.evaluate("n=>window.__apiRequests.slice(n)", request_start))
        page.click("#embeddedInputClose")

        # Direct touchpad targets switch immediately; no window-switch overlay is opened.
        request_start = page.evaluate("window.__apiRequests.length")
        page.click('#topWindows [data-inline-window="chrome"]')
        page.wait_for_timeout(100)
        assert page.evaluate("window.__targetType") == "chrome"
        requests = page.evaluate("n=>window.__apiRequests.slice(n)", request_start)
        assert any(x["path"] == "/core-api/window-switch/chrome" for x in requests), requests
        assert not page.locator("#windowOverlay").evaluate("el=>el.classList.contains('open')")
        page.click('#topWindows [data-inline-window="chatgpt"]')
        page.wait_for_timeout(100)
        assert page.evaluate("window.__targetType") == "chatgpt"
        page.click('#topWindows [data-inline-window="wechat"]')
        page.wait_for_timeout(100)
        assert page.evaluate("window.__targetType") == "wechat"
        assert page.evaluate("window.__wsCount") == 1

        # Touchpad bottom shortcuts are direct: screenshot uses original core key, copy/paste use local hotkeys.
        request_start = page.evaluate("window.__apiRequests.length")
        page.click('[data-pad-shortcut="screenshot"]')
        page.click('[data-pad-shortcut="copy"]')
        page.click('[data-pad-shortcut="paste"]')
        page.wait_for_timeout(80)
        requests = page.evaluate("n=>window.__apiRequests.slice(n)", request_start)
        assert any(x["path"] == "/core-api/key/screenshot" and x["method"] == "POST" for x in requests), requests
        assert any(x["path"] == "/api/hotkey/copy" and x["method"] == "POST" for x in requests), requests
        assert any(x["path"] == "/api/hotkey/paste" and x["method"] == "POST" for x in requests), requests

        # Open/close repeatedly without reconnecting WebSocket.
        for _ in range(20):
            page.click("#keyboardButton")
            page.click("#embeddedInputClose")
        assert page.evaluate("window.__wsCount") == 1

        # Two-finger movement crosses threshold and remains scroll + inertia.
        start = len(frames())
        dispatch_pointer("pointerdown", 1, 110, 260)
        dispatch_pointer("pointerdown", 2, 180, 260)
        for y in [245, 225, 200, 170]:
            dispatch_pointer("pointermove", 1, 110, y)
            dispatch_pointer("pointermove", 2, 180, y)
        dispatch_pointer("pointerup", 1, 110, 170)
        dispatch_pointer("pointerup", 2, 180, 170)
        page.wait_for_timeout(260)
        scroll = [x for x in frames(start) if x.get("type") == "scroll"]
        assert len(scroll) >= 3, scroll

        print(json.dumps({
            "ok": True,
            "websocketCount": page.evaluate("window.__wsCount"),
            "twoFingerHold": True,
            "noIframe": True,
            "draftPersistence": True,
            "backdropDismiss": True,
            "bottomBlankDismiss": True,
            "keyboardDismissAutoClose": True,
            "touchpadShortcuts": ["screenshot", "copy", "paste"],
            "topWindowButtons": True,
            "browserTabNoAccidentalDrag": True,
            "singleHoldDrag": True,
            "singleLongPressRightClick": True,
            "doubleTapHoldDrag": True,
            "legacyInputPageSwitch": True,
            "realtimeAndReadback": True,
            "sendEnterRetryNoDuplicate": True,
            "windowTargets": ["chatgpt", "chrome", "wechat"],
            "repeatedOpenClose": 20,
            "inertiaScrollFrames": len(scroll),
        }, ensure_ascii=False))
        browser.close()


if __name__ == "__main__":
    main()
