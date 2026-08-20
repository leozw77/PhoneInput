(()=>{
'use strict';

const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function create(options={}){
  document.body.insertAdjacentHTML('beforeend',`<div id="textOverlay" class="controlOverlay" aria-hidden="true">
    <section class="controlSheet" role="dialog" aria-modal="true" aria-labelledby="embeddedInputTitle">
      <div class="sheetHeader"><div><strong id="embeddedInputTitle">输入到电脑</strong><small>内置输入 · 不离开触控板</small></div><button id="embeddedInputClose" type="button">返回触控板</button></div>
      <div class="sheetBody"><div id="embeddedInputCard" class="embeddedInputCard">
        <div id="embeddedTarget" class="inputTarget">正在读取当前窗口…</div>
        <div class="quickWindows" aria-label="快速切换窗口"><button data-inline-window="chatgpt">ChatGPT</button><button data-inline-window="chrome">Chrome</button><button data-inline-window="wechat">微信</button></div>
        <textarea id="embeddedText" class="embeddedText" placeholder="输入文字，然后发送到电脑"></textarea>
        <div class="inputOptions">
          <label>模式 <select id="embeddedMode"><option value="batch">批量输入</option><option value="realtime">即时输入</option></select></label>
          <label>速度 <select id="embeddedSpeed"><option value="0">最快</option><option value="6" selected>正常</option><option value="15">稳妥</option></select></label>
          <label id="embeddedEnterModeWrap" style="display:none">手机回车 <select id="embeddedEnterMode"><option value="submit">发送 Enter</option><option value="newline">Shift+Enter 换行</option></select></label>
        </div>
        <div class="inputActions"><button id="embeddedCancel" type="button">取消</button><button id="embeddedSend" class="primary" type="button">发送</button><button id="embeddedSendEnter" class="primary" type="button">发送并回车</button></div>
        <button id="embeddedKeepSend" class="keepSend" type="button">发送但保留输入内容</button>
        <div class="realtimeActions"><button id="embeddedClearLocal" type="button">清空手机输入框</button><button id="embeddedRealtimeEnter" class="primary" type="button">发送 Enter</button></div>
        <button id="embeddedSyncDesktop" class="syncDesktop" type="button">从电脑同步</button>
        <details class="advancedInput"><summary>高级快捷键</summary><div class="advancedGrid">
          <button data-input-key="backspace">Backspace</button><button data-input-key="enter">Enter</button><button data-input-key="tab">Tab</button><button data-input-key="escape">Esc</button>
          <button data-input-key="left">←</button><button data-input-key="up">↑</button><button data-input-key="down">↓</button><button data-input-key="right">→</button>
          <button data-input-key="screenshot">截图</button>
        </div></details>
        <div id="embeddedInputStatus" class="inputStatus">输入内容会保留，关闭弹层不会销毁。</div>
      </div></div>
    </section>
    <button id="embeddedBottomDismiss" class="bottomDismissZone" type="button" aria-label="点击下方空白返回触控板"></button>
  </div>
  <div id="windowOverlay" class="controlOverlay" aria-hidden="true">
    <section class="controlSheet compact" role="dialog" aria-modal="true" aria-labelledby="windowSwitchTitle">
      <div class="sheetHeader"><div><strong id="windowSwitchTitle">切换电脑窗口</strong><small>仅保留常用的三个目标</small></div><button id="windowSwitchClose" type="button">返回触控板</button></div>
      <div class="sheetBody"><div class="windowGrid"><button data-window-target="chatgpt">ChatGPT</button><button data-window-target="chrome">Chrome</button><button data-window-target="wechat">微信</button></div><p class="windowHelp">切换成功后自动返回触控板，不刷新页面，也不会重建触控板 WebSocket。</p></div>
    </section>
  </div><div id="embeddedToast" class="inputToast"></div>`);

  const $=s=>document.querySelector(s);
  const text=$('#embeddedText'), card=$('#embeddedInputCard'), state=$('#embeddedInputStatus'), target=$('#embeddedTarget'), toast=$('#embeddedToast');
  const textOverlay=$('#textOverlay'), windowOverlay=$('#windowOverlay');
  let activeModal='', historyActive=false, historyClosing=false, toastTimer=0, busy=false, selectionTimer=0, composing=false;
  let keyboardBaseline=0,keyboardSeen=false,keyboardCloseTimer=0;
  let queue=Promise.resolve(),refreshQueue=Promise.resolve(),currentTargetId='',currentTargetType='other',lockedTargetId='',sessionStarted=false,suppressSelectionUntil=0,transitionSerial=0,queueGeneration=0,manualCopyPendingTargetId='';
  let drafts={},batchDraft='',pendingEnterAfterText=null;
  const draftStorageKey='phoneInputDrafts-v1.2.1';
  const batchStorageKey='phoneInputEmbeddedBatchDraft-v1';
  const graphemeSegmenter=typeof Intl.Segmenter==='function'?new Intl.Segmenter(undefined,{granularity:'grapheme'}):null;

  function notice(message){toast.textContent=message;toast.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.classList.remove('show'),1700)}
  function setStatus(message,kind=''){state.textContent=message;state.classList.toggle('error',kind==='error');state.classList.toggle('good',kind==='good')}
  function realtime(){return $('#embeddedMode').value==='realtime'}
  function loadDrafts(){try{drafts=JSON.parse(localStorage.getItem(draftStorageKey)||'{}')||{}}catch{drafts={}};batchDraft=localStorage.getItem(batchStorageKey)||''}
  function saveDraft(targetId){if(!targetId)return;drafts[targetId]={value:text.value,start:text.selectionStart??0,end:text.selectionEnd??0,controlId:drafts[targetId]?.controlId||''};localStorage.setItem(draftStorageKey,JSON.stringify(drafts))}
  function restoreDraft(targetId){const draft=drafts[targetId];if(!draft)return false;text.value=String(draft.value||'');const start=Math.min(Math.max(0,+draft.start||0),text.value.length),end=Math.min(Math.max(start,+draft.end||start),text.value.length);text.setSelectionRange(start,end);return true}
  function saveBatch(){batchDraft=text.value;localStorage.setItem(batchStorageKey,batchDraft)}
  function clearSession(){clearTimeout(selectionTimer);lockedTargetId='';sessionStarted=false}
  function invalidateQueue(){queueGeneration++}
  function focusText(immediate=false){const run=()=>{try{text.focus({preventScroll:true});const end=text.selectionEnd??text.value.length;text.setSelectionRange(end,end)}catch{}};if(immediate)run();else setTimeout(run,30)}

  function viewportHeight(){return window.visualViewport?.height||window.innerHeight}
  function resetKeyboardMonitor(){clearTimeout(keyboardCloseTimer);keyboardCloseTimer=0;keyboardBaseline=viewportHeight();keyboardSeen=false;textOverlay.classList.remove('keyboard-visible')}
  function stopKeyboardMonitor(){clearTimeout(keyboardCloseTimer);keyboardCloseTimer=0;keyboardSeen=false;textOverlay.classList.remove('keyboard-visible')}
  function inspectKeyboardViewport(){
    if(activeModal!=='text')return;
    const current=viewportHeight();
    if(!keyboardBaseline)keyboardBaseline=current;
    if(!keyboardSeen&&current>keyboardBaseline)keyboardBaseline=current;
    const drop=keyboardBaseline-current,openThreshold=Math.max(110,keyboardBaseline*.16);
    if(drop>openThreshold){keyboardSeen=true;textOverlay.classList.add('keyboard-visible');clearTimeout(keyboardCloseTimer);keyboardCloseTimer=0;return}
    textOverlay.classList.remove('keyboard-visible');
    if(!keyboardSeen||drop>=80)return;
    clearTimeout(keyboardCloseTimer);
    keyboardCloseTimer=setTimeout(()=>{
      if(activeModal!=='text'||!keyboardSeen)return;
      if(keyboardBaseline-viewportHeight()<80)closeText('keyboard-dismissed');
    },240);
  }

  async function api(url,options={}){
    try{
      const r=await fetch(url,options),x=await r.clone().json().catch(()=>({}));
      if(!r.ok)throw new Error(x.error||x.reason||'电脑端请求失败');
      setStatus('电脑端连接正常','good');return r;
    }catch(e){setStatus(e.message||'电脑端不可用','error');notice(e.message||'电脑端不可用');throw e}
  }
  async function readDesktopState(targetId,requestSource,copyBack=false){
    const query='?targetId='+encodeURIComponent(targetId)+'&source='+encodeURIComponent(requestSource)+(copyBack?'&copyBack=true':'');
    const r=await fetch('/core-api/input-state'+query);const x=await r.json().catch(()=>({}));
    if(!r.ok&&x.reason!=='target-mismatch')throw new Error(x.error||x.reason||'无法读取电脑输入内容');
    return x;
  }
  async function syncDesktopState(targetId,force=false,showNotice=false,allowActive=false,expectedControlId='',requestSource='automatic',copyBack=false){
    if(!targetId)return false;const serial=transitionSerial,expectedValue=text.value,expectedStart=text.selectionStart??0,expectedEnd=text.selectionEnd??0,maxAttempts=requestSource==='manual'?1:4;
    for(let attempt=0;attempt<maxAttempts;attempt++){
      try{
        if(attempt)await new Promise(resolve=>setTimeout(resolve,80));
        const x=await readDesktopState(targetId,requestSource,copyBack);
        if(serial!==transitionSerial||currentTargetId!==targetId||x.targetId!==targetId){if(showNotice)notice('当前窗口已变化，未覆盖手机内容');manualCopyPendingTargetId='';return false}
        if(x.reason==='target-mismatch'){if(showNotice)notice('电脑目标已变化，请重试');manualCopyPendingTargetId='';return false}
        if(!x.supported){
          if(requestSource==='manual'&&x.reason==='google-search-pattern-unavailable'&&!copyBack){manualCopyPendingTargetId=targetId;return false}
          if(copyBack&&requestSource==='manual'&&x.reason==='google-search-pattern-unavailable'){manualCopyPendingTargetId='';if(showNotice)notice('当前输入框暂不支持回读');return false}
          if(attempt<maxAttempts-1)continue;return false;
        }
        if(!force&&sessionStarted&&!allowActive)return false;
        if(expectedControlId&&x.controlId!==expectedControlId){if(showNotice)notice('电脑输入控件已变化，未覆盖手机草稿');manualCopyPendingTargetId='';return false}
        if(allowActive&&(text.value!==expectedValue||(text.selectionStart??0)!==expectedStart||(text.selectionEnd??0)!==expectedEnd))return false;
        text.value=x.text||'';const start=Math.min(Math.max(0,+x.selectionStart||0),text.value.length),end=Math.min(Math.max(start,+x.selectionEnd||start),text.value.length);text.setSelectionRange(start,end);
        drafts[targetId]={value:text.value,start,end,controlId:x.controlId||''};localStorage.setItem(draftStorageKey,JSON.stringify(drafts));manualCopyPendingTargetId='';
        if(showNotice)notice('已从电脑同步');setStatus('已读取电脑当前输入内容','good');return true;
      }catch(e){if(attempt===maxAttempts-1){if(showNotice)notice(e.message||'同步失败');return false}}
    }
    return false;
  }
  async function switchTarget(previousTargetId,nextTargetId,nextTargetType='other'){
    const serial=++transitionSerial;if(previousTargetId&&previousTargetId!==nextTargetId&&realtime())saveDraft(previousTargetId);invalidateQueue();clearSession();if(previousTargetId!==nextTargetId)manualCopyPendingTargetId='';
    if(!realtime())return;
    text.value='';const draft=drafts[nextTargetId],hasDraft=restoreDraft(nextTargetId);if(hasDraft&&text.value){lockedTargetId=nextTargetId;sessionStarted=true}
    if(['chatgpt','wechat','chrome'].includes(nextTargetType)){
      const synced=await syncDesktopState(nextTargetId,false,false,hasDraft,draft?.controlId||'','automatic');if(!synced&&serial===transitionSerial&&activeModal==='text'&&!text.value)notice('已保留手机草稿/当前状态');
    }
    if(serial===transitionSerial&&activeModal==='text')focusText();
  }
  async function refreshCore(windowSwitch=false){
    try{
      const r=await api('/core-api/status'),x=await r.json();const nextTargetId=x.targetId||'',previousTargetId=currentTargetId,nextTargetType=x.targetType||'other';
      currentTargetId=nextTargetId;currentTargetType=nextTargetType;
      if((realtime()&&nextTargetId!==previousTargetId)||(windowSwitch&&nextTargetId))await switchTarget(previousTargetId,nextTargetId,nextTargetType);
      const changed=realtime()&&lockedTargetId&&currentTargetId!==lockedTargetId;target.textContent=(changed?'窗口已变化 · ':'')+(x.target||'未识别当前输入窗口');target.classList.toggle('changed',!!changed);
      return x;
    }catch{return null}
  }
  function refresh(windowSwitch=false){const next=refreshQueue.then(()=>refreshCore(windowSwitch));refreshQueue=next.catch(()=>{});return next}

  function caretSteps(value,utf16Offset){const prefix=value.slice(0,utf16Offset);return graphemeSegmenter?Array.from(graphemeSegmenter.segment(prefix)).length:Array.from(prefix).length}
  function enqueueText(value){
    if(!value)return;if(realtime()){
      if(!sessionStarted){if(!currentTargetId){notice('未识别当前电脑窗口');return}lockedTargetId=currentTargetId;sessionStarted=true}
      else if(!lockedTargetId||currentTargetId!==lockedTargetId){notice('电脑窗口已变化，请先同步');return}
    }
    const delayMs=+$('#embeddedSpeed').value,generation=queueGeneration,targetId=currentTargetId;
    queue=queue.then(()=>generation===queueGeneration?api('/core-api/text',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:value,delayMs,targetId:realtime()?targetId:null})}):undefined).catch(()=>{});
  }
  function enqueueKey(key){const generation=queueGeneration,targetId=currentTargetId;queue=queue.then(()=>generation===queueGeneration?api('/core-api/key/'+encodeURIComponent(key)+'?targetId='+encodeURIComponent(targetId),{method:'POST'}):undefined).catch(()=>{})}
  function enqueueSelection(start,end){
    if(!sessionStarted||!text.value)return;if(!lockedTargetId||currentTargetId!==lockedTargetId){notice('电脑窗口已变化，请先同步');return}
    const generation=queueGeneration;queue=queue.then(()=>generation===queueGeneration&&api('/core-api/selection',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({start:caretSteps(text.value,start),end:caretSteps(text.value,end),targetId:lockedTargetId})})).catch(()=>{});
  }
  function scheduleSelectionSync(force=false,delay=40){if(!realtime()||!sessionStarted||!text.value||composing||document.activeElement!==text)return;if(!force&&Date.now()<suppressSelectionUntil)return;clearTimeout(selectionTimer);selectionTimer=setTimeout(()=>{if(realtime()&&sessionStarted&&text.value&&!composing&&document.activeElement===text)enqueueSelection(text.selectionStart??0,text.selectionEnd??0)},delay)}

  function saveSettings(){localStorage.setItem('phoneInputSettings',JSON.stringify({mode:$('#embeddedMode').value,speed:$('#embeddedSpeed').value,enterMode:$('#embeddedEnterMode').value}))}
  function loadSettings(){try{const x=JSON.parse(localStorage.getItem('phoneInputSettings')||'{}');if(x.mode)$('#embeddedMode').value=x.mode;if(x.speed)$('#embeddedSpeed').value=x.speed;if(x.enterMode)$('#embeddedEnterMode').value=x.enterMode}catch{}}
  async function changeMode(){
    const active=realtime();invalidateQueue();clearSession();card.classList.toggle('realtime',active);$('#embeddedEnterModeWrap').style.display=active?'flex':'none';
    if(active){saveBatch();text.value='';restoreDraft(currentTargetId);text.placeholder='即时输入：手机输入会实时发送到当前电脑输入框';await refresh();if(currentTargetId&&!text.value)await syncDesktopState(currentTargetId,false,false,false,drafts[currentTargetId]?.controlId||'','automatic')}
    else{if(currentTargetId)saveDraft(currentTargetId);text.value=batchDraft;text.placeholder='输入完整文字，然后点击发送';}
    pendingEnterAfterText=null;updatePendingEnterUI();saveSettings();focusText();
  }

  function updatePendingEnterUI(){const b=$('#embeddedSendEnter');text.readOnly=!!pendingEnterAfterText;if(pendingEnterAfterText){b.textContent='重试回车';b.classList.add('dangerState');$('#embeddedSend').disabled=true;$('#embeddedKeepSend').disabled=true;setStatus('文字已发送，但 Enter 未成功；“重试回车”只会补发 Enter，不会重复文字。','error')}else{b.textContent='发送并回车';b.classList.remove('dangerState');$('#embeddedSend').disabled=false;$('#embeddedKeepSend').disabled=false}}
  async function sendBatch({closeAfter=true,enterAfter=false,keep=false}={}){
    if(busy||realtime())return;const value=text.value;if(!value&&!pendingEnterAfterText)return;
    busy=true;$('#embeddedSend').disabled=$('#embeddedSendEnter').disabled=$('#embeddedKeepSend').disabled=true;
    try{
      if(enterAfter&&pendingEnterAfterText){await api('/api/key/enter?source=send-and-enter',{method:'POST'});pendingEnterAfterText=null;updatePendingEnterUI();text.value='';saveBatch();notice('回车已补发');if(closeAfter)closeText('send-enter-retry-success');return}
      if(!value)return;
      await api('/api/text',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:value,delayMs:+$('#embeddedSpeed').value,enterAfter:false})});
      if(enterAfter){pendingEnterAfterText={text:value,time:Date.now()};updatePendingEnterUI();try{await api('/api/key/enter?source=send-and-enter',{method:'POST'});pendingEnterAfterText=null;updatePendingEnterUI()}catch{return}}
      if(!keep){text.value='';saveBatch()}else saveBatch();notice(enterAfter?'文字与回车已发送':'文字已发送');if(closeAfter&&!keep)closeText(enterAfter?'send-enter-success':'batch-send-success');else focusText();
    }catch{}finally{busy=false;if(!pendingEnterAfterText){$('#embeddedSend').disabled=$('#embeddedSendEnter').disabled=$('#embeddedKeepSend').disabled=false}else{$('#embeddedSendEnter').disabled=false;updatePendingEnterUI()}}
  }
  async function sendKey(key){
    if(realtime()){if(key==='enter'){enqueueKey('enter');text.value='';clearSession();notice('已发送 Enter');return}enqueueKey(key);return}
    try{await api('/core-api/key/'+encodeURIComponent(key),{method:'POST'});notice('已发送 '+key)}catch{}
  }
  async function switchWindow(name,closeAfter){
    const buttons=[...document.querySelectorAll('[data-window-target],[data-inline-window]')];buttons.forEach(b=>b.disabled=true);
    try{await api('/core-api/window-switch/'+encodeURIComponent(name),{method:'POST'});await refresh(true);notice('已切换到 '+({chatgpt:'ChatGPT',chrome:'Chrome',wechat:'微信'}[name]||name));if(closeAfter)closeWindows('window-switch-success')}
    catch{}finally{buttons.forEach(b=>b.disabled=false)}
  }

  function activateModal(kind,source){
    if(activeModal===kind){if(kind==='text')focusText();return}
    if(activeModal)deactivateModal('modal-replaced',false);
    options.onBeforeOpen?.(kind,source);activeModal=kind;const overlay=kind==='text'?textOverlay:windowOverlay;overlay.classList.add('open');overlay.setAttribute('aria-hidden','false');
    if(!historyActive){history.pushState({phoneInputOverlay:true},'');historyActive=true}options.onOpen?.(kind,source);
  }
  function deactivateModal(reason,consumeHistory=true){
    if(!activeModal)return;const kind=activeModal,overlay=kind==='text'?textOverlay:windowOverlay;if(kind==='text')stopKeyboardMonitor();try{document.activeElement?.blur()}catch{};overlay.classList.remove('open');overlay.setAttribute('aria-hidden','true');activeModal='';options.onClose?.(kind,reason);
    if(consumeHistory&&historyActive){historyActive=false;historyClosing=true;history.back()}else historyActive=false;
  }
  async function openText(source='button'){
    activateModal('text',source);resetKeyboardMonitor();focusText(true);await refresh();
    if(realtime()&&currentTargetId&&!text.value&&!sessionStarted)await syncDesktopState(currentTargetId,false,false,false,drafts[currentTargetId]?.controlId||'','automatic');
    focusText();setTimeout(focusText,180);
  }
  function closeText(reason='close-button'){if(realtime()&&currentTargetId)saveDraft(currentTargetId);else saveBatch();deactivateModal(reason,true)}
  function openWindows(source='button'){activateModal('windows',source)}
  function closeWindows(reason='close-button'){deactivateModal(reason,true)}

  $('#embeddedInputClose').onclick=()=>closeText('close-button');$('#embeddedCancel').onclick=()=>closeText('cancel-button');$('#embeddedBottomDismiss').onclick=()=>closeText('bottom-blank-tap');$('#windowSwitchClose').onclick=()=>closeWindows('close-button');
  textOverlay.addEventListener('pointerdown',e=>{if(e.target===textOverlay){e.preventDefault();closeText('backdrop-tap')}});windowOverlay.addEventListener('pointerdown',e=>{if(e.target===windowOverlay){e.preventDefault();closeWindows('backdrop-tap')}});
  $('#embeddedSend').onclick=()=>sendBatch({closeAfter:true});$('#embeddedSendEnter').onclick=()=>sendBatch({closeAfter:true,enterAfter:true});$('#embeddedKeepSend').onclick=()=>sendBatch({closeAfter:false,keep:true});
  $('#embeddedMode').onchange=changeMode;$('#embeddedSpeed').onchange=saveSettings;$('#embeddedEnterMode').onchange=saveSettings;
  $('#embeddedClearLocal').onclick=()=>{invalidateQueue();text.blur();composing=false;text.value='';clearSession();if(currentTargetId)saveDraft(currentTargetId);setTimeout(focusText,60);notice('手机输入框已清空')};
  $('#embeddedRealtimeEnter').onclick=()=>{enqueueKey('enter');text.value='';clearSession();if(currentTargetId)saveDraft(currentTargetId);focusText();notice('已发送 Enter')};
  $('#embeddedSyncDesktop').onclick=async()=>{if(!currentTargetId){notice('未识别当前电脑窗口');return}const expectedControlId=drafts[currentTargetId]?.controlId||'',copyBack=manualCopyPendingTargetId===currentTargetId;const synced=await syncDesktopState(currentTargetId,true,true,false,expectedControlId,'manual',copyBack);if(!synced&&manualCopyPendingTargetId===currentTargetId)notice('再点一次“从电脑同步”可尝试复制回读')};
  document.querySelectorAll('[data-input-key]').forEach(b=>b.onclick=()=>sendKey(b.dataset.inputKey));document.querySelectorAll('[data-inline-window]').forEach(b=>b.onclick=()=>switchWindow(b.dataset.inlineWindow,false));document.querySelectorAll('[data-window-target]').forEach(b=>b.onclick=()=>switchWindow(b.dataset.windowTarget,true));

  text.addEventListener('compositionstart',()=>{clearTimeout(selectionTimer);if(realtime())enqueueSelection(text.selectionStart??0,text.selectionEnd??0);composing=true});
  text.addEventListener('compositionend',event=>{composing=false;if(realtime()&&event.data)enqueueText(event.data)});
  text.addEventListener('beforeinput',event=>{
    if(!realtime()||composing)return;
    if(event.inputType==='insertText'&&event.data){if(text.selectionStart!==text.selectionEnd){clearTimeout(selectionTimer);enqueueSelection(text.selectionStart??0,text.selectionEnd??0)}enqueueText(event.data)}
    else if(event.inputType==='insertLineBreak'){event.preventDefault();if($('#embeddedEnterMode').value==='newline'){enqueueKey('shift-enter');text.setRangeText('\n',text.selectionStart,text.selectionEnd,'end');suppressSelectionUntil=Date.now()+180;notice('已发送 Shift+Enter')}else{enqueueKey('enter');text.value='';clearSession();notice('已发送 Enter')}}
    else if(event.inputType==='deleteContentBackward'){if(text.selectionStart!==text.selectionEnd){clearTimeout(selectionTimer);enqueueSelection(text.selectionStart??0,text.selectionEnd??0)}enqueueKey('backspace')}
    else if(event.inputType==='deleteByCut'||event.inputType==='deleteByDrag'){clearTimeout(selectionTimer);enqueueSelection(text.selectionStart??0,text.selectionEnd??0);enqueueKey('backspace')}
    else if((event.inputType==='insertFromPaste'||event.inputType==='insertFromDrop')&&event.data)enqueueText(event.data);
  });
  text.addEventListener('paste',event=>{if(!realtime())return;const value=event.clipboardData?.getData('text');if(value){event.preventDefault();text.setRangeText(value,text.selectionStart,text.selectionEnd,'end');enqueueText(value)}});
  text.addEventListener('input',()=>{suppressSelectionUntil=Date.now()+180;if(realtime()&&currentTargetId)saveDraft(currentTargetId);else saveBatch()});
  document.addEventListener('selectionchange',()=>scheduleSelectionSync(false,120));text.addEventListener('selectionchange',()=>scheduleSelectionSync(false,120));text.addEventListener('select',()=>scheduleSelectionSync(true,40));text.addEventListener('click',()=>scheduleSelectionSync(true,30));text.addEventListener('pointerup',()=>scheduleSelectionSync(true,30));text.addEventListener('touchend',()=>scheduleSelectionSync(true,40),{passive:true});
  window.addEventListener('popstate',()=>{if(historyClosing){historyClosing=false;return}if(activeModal)deactivateModal('system-back',false)});
  window.visualViewport?.addEventListener('resize',inspectKeyboardViewport);window.addEventListener('resize',inspectKeyboardViewport);

  loadDrafts();loadSettings();card.classList.toggle('realtime',realtime());$('#embeddedEnterModeWrap').style.display=realtime()?'flex':'none';if(realtime()){restoreDraft(currentTargetId)}else{text.value=batchDraft}saveSettings();refresh();setInterval(refresh,2000);

  return {openText,closeText,openWindows,closeWindows,focusText,refresh,isOpen:()=>!!activeModal,activeKind:()=>activeModal};
}

window.PhoneInputEmbeddedInput={create};
})();
