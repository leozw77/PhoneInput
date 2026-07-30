namespace PhoneInput;

internal static class MobilePage
{
    public const string Html = """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#111827">
  <meta name="mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <link rel="manifest" href="/manifest.webmanifest">
  <title>手机输入到电脑</title>
  <style>
    :root{color-scheme:dark;--bg:#0b1020;--card:#151c2e;--line:#2a3550;--text:#f4f7ff;--muted:#9ca9c2;--accent:#6d8cff;--good:#42d392}
    *{box-sizing:border-box}body{margin:0;background:linear-gradient(150deg,#111a34,var(--bg) 55%);color:var(--text);font-family:system-ui,-apple-system,"Segoe UI",sans-serif;min-height:100vh}
    main{width:min(720px,100%);margin:auto;padding:calc(18px + env(safe-area-inset-top)) 16px calc(24px + env(safe-area-inset-bottom))}
    header{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}h1{font-size:22px;margin:0}.state{font-size:13px;color:var(--good)}
    .target{font-size:13px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-bottom:8px}
    .install{display:block;margin:0 0 12px auto;padding:7px 10px;border:1px solid var(--line);background:transparent;color:var(--muted);font-size:12px}
    .card{background:rgba(21,28,46,.94);border:1px solid var(--line);border-radius:18px;padding:14px;box-shadow:0 16px 50px #0005}
    textarea{display:block;width:100%;min-height:210px;resize:vertical;border:0;outline:0;border-radius:12px;background:#0e1528;color:var(--text);padding:15px;font:18px/1.55 system-ui}
    textarea::placeholder{color:#71809e}.options{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:12px 2px;color:var(--muted);font-size:14px}
    select{background:#0e1528;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px}
    button{border:0;border-radius:12px;padding:13px 10px;background:#26324d;color:var(--text);font-size:16px;touch-action:manipulation}
    button:active{transform:scale(.97)}.primary{background:var(--accent);font-weight:700}.actions{display:grid;grid-template-columns:1fr 1fr;gap:10px}
    .keys{display:grid;grid-template-columns:repeat(4,1fr);gap:9px;margin-top:12px}.keys button{font-size:14px}
    .realtime-tools{display:none}.realtime .actions{display:none}.realtime .realtime-tools{display:grid}
    .hint{color:var(--muted);font-size:12px;line-height:1.5;margin:12px 3px 0}.toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#f4f7ff;color:#111827;padding:10px 16px;border-radius:999px;opacity:0;pointer-events:none;transition:.2s}.toast.show{opacity:1}
  </style>
</head>
<body>
<main>
  <header><h1>手机输入到电脑</h1><span id="state" class="state">正在连接</span></header>
  <div id="target" class="target">正在读取电脑当前窗口…</div>
  <button id="install" class="install">添加到主屏幕</button>
  <section id="card" class="card">
    <textarea id="text" autofocus placeholder="在这里使用手机输入法输入文字…"></textarea>
    <div class="options">
      <label>模式
        <select id="mode"><option value="batch">整段发送</option><option value="realtime">即时输入</option></select>
      </label>
      <label><input id="enterAfter" type="checkbox"> 发送后回车</label>
      <label id="enterModeWrap" style="display:none">手机回车
        <select id="enterMode">
          <option value="submit">提交并清空</option>
          <option value="newline">换行，不提交</option>
        </select>
      </label>
      <label>速度
        <select id="speed"><option value="0">快速</option><option value="6" selected>兼容</option><option value="15">慢速</option></select>
      </label>
    </div>
    <div class="actions">
      <button id="send" class="primary">发送并清空</button>
      <button id="keep">发送并保留</button>
    </div>
    <div class="actions realtime-tools">
      <button id="clearLocal">清空手机输入区</button>
      <button id="realtimeEnter" class="primary">↵ 回车</button>
    </div>
    <div class="keys">
      <button data-key="backspace">⌫ 退格</button><button data-key="enter">↵ 回车</button>
      <button data-key="tab">Tab</button><button data-key="escape">Esc</button>
      <button data-key="left">←</button><button data-key="up">↑</button>
      <button data-key="down">↓</button><button data-key="right">→</button>
    </div>
    <p id="hint" class="hint">文字会进入电脑当前光标位置。发送前请确认电脑焦点位于正确的输入框。</p>
  </section>
</main>
<div id="toast" class="toast"></div>
<script>
  const $=s=>document.querySelector(s), text=$('#text'), state=$('#state'), target=$('#target'), toast=$('#toast');
  let busy=false, toastTimer, selectionTimer, composing=false, queue=Promise.resolve(), installPrompt=null;
  let currentTargetId='', lockedTargetId='', suppressSelectionUntil=0;
  const graphemeSegmenter=typeof Intl.Segmenter==='function'
    ?new Intl.Segmenter(undefined,{granularity:'grapheme'}):null;
  function notice(message){toast.textContent=message;toast.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.classList.remove('show'),1500)}
  async function request(url,options){
    try{const r=await fetch(url,options);if(!r.ok){let x=await r.json().catch(()=>({}));throw new Error(x.error||'请求失败')}state.textContent='已连接';return r}
    catch(e){state.textContent='连接断开';notice(e.message);throw e}
  }
  async function send(clear){
    if(busy||!text.value)return;
    busy=true;$('#send').disabled=$('#keep').disabled=true;
    try{
      await request('/api/text',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:text.value,delayMs:+$('#speed').value,enterAfter:$('#enterAfter').checked})});
      if(clear)text.value='';notice('已发送');text.focus();
    }finally{busy=false;$('#send').disabled=$('#keep').disabled=false}
  }
  $('#send').onclick=()=>send(true);$('#keep').onclick=()=>send(false);
  document.querySelectorAll('[data-key]').forEach(b=>b.onclick=async()=>{
    await request('/api/key/'+b.dataset.key,{method:'POST'});
    if(realtime()&&b.dataset.key==='enter')text.value='';
    notice('已发送 '+b.textContent);
  });
  function enqueueText(value){
    if(!value)return;
    if(realtime()&&currentTargetId)lockedTargetId=currentTargetId;
    const delayMs=+$('#speed').value;
    queue=queue.then(()=>request('/api/text',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:value,delayMs})})).catch(()=>{});
  }
  function enqueueKey(key){queue=queue.then(()=>request('/api/key/'+key,{method:'POST'})).catch(()=>{})}
  function caretSteps(value,utf16Offset){
    const prefix=value.slice(0,utf16Offset);
    if(graphemeSegmenter)return Array.from(graphemeSegmenter.segment(prefix)).length;
    return Array.from(prefix).length;
  }
  function enqueueSelection(start,end){
    if(currentTargetId)lockedTargetId=currentTargetId;
    if(!lockedTargetId){notice('尚未检测到电脑目标窗口');return}
    queue=queue.then(()=>request('/api/selection',{
      method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({
        start:caretSteps(text.value,start),
        end:caretSteps(text.value,end),
        targetId:lockedTargetId
      })
    })).then(()=>{state.textContent='光标已同步'}).catch(()=>{});
  }
  function scheduleSelectionSync(force=false,delay=40){
    if(!realtime()||composing||document.activeElement!==text)return;
    if(!force&&Date.now()<suppressSelectionUntil)return;
    clearTimeout(selectionTimer);
    selectionTimer=setTimeout(()=>{
      if(!realtime()||composing||document.activeElement!==text)return;
      enqueueSelection(text.selectionStart??0,text.selectionEnd??0);
    },delay);
  }
  function realtime(){return $('#mode').value==='realtime'}
  function saveSettings(){
    localStorage.setItem('phoneInputSettings',JSON.stringify({
      mode:$('#mode').value,speed:$('#speed').value,enterMode:$('#enterMode').value
    }));
  }
  function loadSettings(){
    try{
      const x=JSON.parse(localStorage.getItem('phoneInputSettings')||'{}');
      if(x.mode)$('#mode').value=x.mode;
      if(x.speed)$('#speed').value=x.speed;
      if(x.enterMode)$('#enterMode').value=x.enterMode;
    }catch{}
  }
  $('#mode').onchange=()=>{
    const active=realtime();
    lockedTargetId=active?currentTargetId:'';
    $('#card').classList.toggle('realtime',active);
    $('#enterAfter').parentElement.style.display=active?'none':'';
    $('#enterModeWrap').style.display=active?'':'none';
    text.placeholder=active?'开始输入；中文会在选词确认后发送…':'在这里使用手机输入法输入文字…';
    $('#hint').textContent=active
      ?'即时模式：中文选词确认后发送，英文和数字逐次发送；清空输入区不会删除电脑文字。'
      :'文字会进入电脑当前光标位置。发送前请确认电脑焦点位于正确的输入框。';
    text.value='';text.focus();saveSettings();notice(active?'已开启即时输入':'已切换到整段发送');
  };
  $('#speed').onchange=saveSettings;
  $('#enterMode').onchange=saveSettings;
  text.addEventListener('compositionstart',()=>{
    clearTimeout(selectionTimer);
    if(realtime())enqueueSelection(text.selectionStart??0,text.selectionEnd??0);
    composing=true;
  });
  text.addEventListener('compositionend',event=>{
    composing=false;
    if(realtime()&&event.data)enqueueText(event.data);
  });
  text.addEventListener('beforeinput',event=>{
    if(!realtime()||composing)return;
    if(event.inputType==='insertText'&&event.data){
      if(text.selectionStart!==text.selectionEnd){
        clearTimeout(selectionTimer);
        enqueueSelection(text.selectionStart??0,text.selectionEnd??0);
      }
      enqueueText(event.data);
    }
    else if(event.inputType==='insertLineBreak'){
      event.preventDefault();
      if($('#enterMode').value==='newline'){
        enqueueKey('shift-enter');
        text.setRangeText('\n',text.selectionStart,text.selectionEnd,'end');
        suppressSelectionUntil=Date.now()+180;
        notice('已换行');
      }else{
        enqueueKey('enter');text.value='';notice('已发送回车');
      }
    }
    else if(event.inputType==='deleteContentBackward'){
      if(text.selectionStart!==text.selectionEnd){
        clearTimeout(selectionTimer);
        enqueueSelection(text.selectionStart??0,text.selectionEnd??0);
      }
      enqueueKey('backspace');
    }
    else if(event.inputType==='deleteByCut'||event.inputType==='deleteByDrag'){
      clearTimeout(selectionTimer);
      enqueueSelection(text.selectionStart??0,text.selectionEnd??0);
      enqueueKey('backspace');
    }
    else if((event.inputType==='insertFromPaste'||event.inputType==='insertFromDrop')&&event.data)enqueueText(event.data);
  });
  text.addEventListener('paste',event=>{
    if(!realtime())return;
    const value=event.clipboardData?.getData('text');
    if(value){event.preventDefault();text.setRangeText(value,text.selectionStart,text.selectionEnd,'end');enqueueText(value)}
  });
  text.addEventListener('input',()=>{suppressSelectionUntil=Date.now()+180});
  document.addEventListener('selectionchange',()=>scheduleSelectionSync(false,120));
  text.addEventListener('selectionchange',()=>scheduleSelectionSync(false,120));
  text.addEventListener('select',()=>scheduleSelectionSync(true,40));
  text.addEventListener('click',()=>scheduleSelectionSync(true,30));
  text.addEventListener('pointerup',()=>scheduleSelectionSync(true,30));
  text.addEventListener('touchend',()=>scheduleSelectionSync(true,40),{passive:true});
  $('#clearLocal').onclick=()=>{
    text.blur();composing=false;text.value='';
    setTimeout(()=>{text.value='';text.focus()},80);
    notice('手机输入区已清空');
  };
  $('#realtimeEnter').onclick=()=>{enqueueKey('enter');text.value='';text.focus();notice('已发送回车')};
  window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();installPrompt=event});
  $('#install').onclick=async()=>{
    if(installPrompt){installPrompt.prompt();await installPrompt.userChoice;installPrompt=null}
    else notice('请打开浏览器菜单，选择“添加到主屏幕”');
  };
  async function refresh(){
    try{
      const r=await request('/api/status');const x=await r.json();currentTargetId=x.targetId||'';
      if(realtime()&&!lockedTargetId)lockedTargetId=currentTargetId;
      const changed=realtime()&&lockedTargetId&&currentTargetId!==lockedTargetId;
      target.textContent=(changed?'⚠ 目标已变化：':'当前目标：')+x.target;
      target.style.color=changed?'#ffb86b':'';
    }catch{}
  }
  loadSettings();$('#mode').onchange();
  if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
  refresh();setInterval(refresh,2000);
</script>
</body>
</html>
""";

    public const string Manifest = """
{
  "name": "手机输入到电脑",
  "short_name": "手机输入",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#0b1020",
  "theme_color": "#111827",
  "icons": [
    {
      "src": "/icon.svg",
      "sizes": "any",
      "type": "image/svg+xml",
      "purpose": "any maskable"
    }
  ]
}
""";

    public const string ServiceWorker = """
const CACHE='phone-input-v1';
self.addEventListener('install',event=>{
  event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(['/','/manifest.webmanifest','/icon.svg'])));
  self.skipWaiting();
});
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',event=>{
  if(event.request.method!=='GET')return;
  event.respondWith(fetch(event.request).catch(()=>caches.match(event.request)));
});
""";

    public const string IconSvg = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" rx="112" fill="#111827"/>
  <rect x="106" y="72" width="300" height="368" rx="42" fill="#6d8cff"/>
  <rect x="135" y="112" width="242" height="230" rx="18" fill="#f4f7ff"/>
  <path d="M166 170h180M166 220h142M166 270h104" stroke="#111827" stroke-width="24" stroke-linecap="round"/>
  <circle cx="256" cy="390" r="18" fill="#f4f7ff"/>
</svg>
""";
}
