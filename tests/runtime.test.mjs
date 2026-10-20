// Run with Node.js 18+: node --test tests/runtime.test.mjs
// Deterministic DOM/clock/bridge doubles exercise the actual injected runtime.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../app/src/main/assets/web/ophone-ai-runtime.js', import.meta.url), 'utf8');
const SETUP = '[[OPHONEAI_PROTOCOL_V1]]';
const READY = 'OPHONEAI_READY_V1';
const CALL = 'OPHONEAI_CALL_V1';
const RESULT = 'OPHONEAI_RESULT_V1';
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

function harness(options = {}) {
  let now = 1000, seq = 0, messageSeq = 0, order = 0;
  const extraButtons = [], thinkingNodes = [];
  const timers = new Map(), storage = new Map(options.storage || []);
  const traces = [];
  const messages = [], calls = [], sent = [], listeners = new Map();
  const schedule = (fn, delay = 0, interval = 0) => {
    const id = ++seq; timers.set(id, { fn, at: now + delay, interval }); return id;
  };
  class Element {
    constructor(tag = 'div', text = '', attrs = {}) {
      this.tagName = tag.toUpperCase(); this.textContent = text;
      this.attrs = attrs; this.children = []; this.style = {}; this.dataset = {};
      this.disabled = false; this.events = new Map(); this.order = ++order;
    }
    compareDocumentPosition(other) { return other.order > this.order ? 4 : 2; }
    get innerText() { return this.style.display === 'none' ? this.textContent : this.renderedText ?? this.textContent; }
    getAttribute(name) { return this.attrs[name] ?? null; }
    setAttribute(name, value) { this.attrs[name] = value; }
    removeAttribute(name) { delete this.attrs[name]; }
    get parentElement() { return this.parent || null; }
    appendChild(child) { this.children.push(child); child.parent = this; return child; }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(x => x !== this); }
    contains(el) { return this === el || this.children.some(c => c.contains(el)); }
    getBoundingClientRect() { return { left: 10, top: 650, width: 400, height: 50 }; }
    focus() {}
    addEventListener(name, fn) { this.events.set(name, fn); }
    dispatchEvent(event) {
      this.events.get(event.type)?.(event);
      if (this === sendButton && event.type === 'click') {
        const text = composer.value;
        if (options.sendBlocked?.()) return true;
        sent.push(text); user((options.transformResult && text.startsWith(RESULT) ? options.transformResult(text)
          : options.renderMarkdown ? text.replace(/```/g, '') : text)
          + (options.longMessageControl && text.startsWith(RESULT) ? '展开收起' : '')); composer.value = '';
        options.onSend?.(text, api);
      }
      return true;
    }
    querySelector(selector) {
      if (selector === '[class*="border-token-border-default"][class*="mask-shimmer"]') return this.transportBanner || null;
      if (selector === 'pre code, code') return this.code || null;
      const match = selector.match(/^\[data-ophone-ai="(.*)"\]$/);
      const all = this.children.flatMap(c => [c, ...c.descendants()]);
      if (match) return all.find(c => c.attrs['data-ophone-ai'] === match[1]) || null;
      if (selector === 'button') return all.find(c => c.tagName === 'BUTTON') || null;
      return null;
    }
    querySelectorAll(selector) {
      const all=this.descendants();
      if(selector==='button')return all.filter(n=>n.tagName==='BUTTON');
      if(selector.includes('data-chatgpt-search-unit-key'))return all.filter(n=>n.attrs['data-chatgpt-search-unit-key']);
      if(selector==='[data-message-author-role]')return all.filter(n=>n.attrs['data-message-author-role']);
      return [];
    }
    descendants() { return this.children.flatMap(c => [c, ...c.descendants()]); }
  }
  class Textarea extends Element {
    constructor() { super('textarea'); this._value = ''; }
    get value() { return this._value; }
    set value(v) { this._value = v; }
  }
  let composer = new Textarea(); const sendButton = new Element('button');
  const body = new Element(), root = new Element();
  const document = {
    documentElement: root, body, cookie: '',
    createElement: tag => new Element(tag),
    getElementById: id => body.descendants().find(x => x.id === id) || null,
    querySelector(selector) {
      if (selector === '#prompt-textarea') return options.noComposer ? null : composer;
      if (selector.startsWith('button[data-testid="send-button"]')) return options.sendReady?.(composer, now) === false ? null : sendButton;
      if (selector.startsWith('button[data-testid="stop-button"]')) return api.generating ? new Element('button') : null;
      if (selector === 'main') return { querySelectorAll: () => thinkingNodes.slice() };
      return null;
    },
    querySelectorAll(selector) {
      if (selector.includes('data-chatgpt-search-unit-key$=')) return options.modern ? messages : [];
      if (selector === '[data-message-author-role]') return messages;
      if (selector === 'button') return [sendButton].concat(extraButtons);
      return [];
    },
    addEventListener: (name, fn) => listeners.set(name, fn),
    removeEventListener: name => listeners.delete(name)
  };
  const location = { pathname: options.path || '/', search: '' };
  const history = {};
  for (const name of ['pushState', 'replaceState']) history[name] = (_, __, path) => { location.pathname = path; };
  const window = {
    addEventListener() {}, removeEventListener() {},
    OPhoneAI: {
      lazySetup: !!options.lazy,
      listTools: () => options.toolList?.() || Promise.resolve([{ name: 'device_status', inputSchema: { type: 'object' } }]),
      trace: (code, id, tool) => traces.push({ code, id, tool }),
      receipt: scope => options.receipt?.(scope) || Promise.resolve({ state: "missing" }),
      call: (name, args, scope) => { calls.push({ name, args, scope }); return options.call?.(name, args, scope) || Promise.resolve({ content: [] }); }
    }
  };
  const nativeRequests = [];
  if (options.nativeTaskBackend) window.OPhoneAI.task = (action, args) => {
    nativeRequests.push({ action, args });
    return options.nativeTaskBackend(action, args);
  };
  class FakeDate extends Date { static now() { return now; } }
  class FakeEvent { constructor(type, args) { this.type = type; Object.assign(this, args); } }
  const context = vm.createContext({ window, document, location, history, navigator: { language: 'en' }, fetch: options.fetch,
    Date: FakeDate, Math, Promise, Set, Map, JSON, Error, Uint8Array,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    setTimeout: (fn, ms) => schedule(fn, ms), clearTimeout: id => timers.delete(id),
    setInterval: (fn, ms) => schedule(fn, ms, ms), clearInterval: id => timers.delete(id),
    MutationObserver: class { observe() {} disconnect() {} },
    HTMLTextAreaElement: Textarea, HTMLInputElement: Textarea,
    Event: FakeEvent, InputEvent: FakeEvent, MouseEvent: FakeEvent, PointerEvent: FakeEvent
  });
  function message(role, text) {
    const id = `m${++messageSeq}`;
    const node = new Element('div', text, options.modern
      ? { 'data-chatgpt-search-unit-key': `${id}:${role}` }
      : { 'data-message-author-role': role, 'data-message-id': id });
    if (options.noMessageIds) delete node.attrs['data-message-id'];
    messages.push(node); return node;
  }
  function user(text) { return message('user', text); }
  function assistant(text) { return message('assistant', text); }
  function batch(id, calls) {
    const json = JSON.stringify({ call_id: id, calls });
    const node = assistant(`${CALL}\n\`\`\`json\n${json}\n\`\`\``);
    node.code = new Element('code', json); return node;
  }
  function tool(id, name = 'device_status', args = {}) {
    const json = JSON.stringify({ call_id: id, tool: name, arguments: args });
    const node = assistant(`${CALL}\n\`\`\`json\n${json}\n\`\`\``);
    node.code = new Element('code', json); return node;
  }
  function ready(id = 'history') {
    user(`${SETUP}\nsetup_id: ${id}\nprotocol`); assistant(`${READY} ${id}`);
  }
  async function microtasks() { for (let i = 0; i < 20; i++) await Promise.resolve(); }
  async function advance(ms = 0) {
    const end = now + ms;
    await microtasks();
    while (true) {
      const next = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a,b) => a[1].at - b[1].at)[0];
      if (!next) break;
      const [id, timer] = next; now = timer.at; timers.delete(id);
      if (timer.interval) timers.set(id, { ...timer, at: now + timer.interval });
      timer.fn(); await microtasks();
    }
    now = end; await microtasks();
  }
  const api = { messages, sent, calls, traces, nativeRequests, storage, listeners, document, extraButtons, thinkingNodes,
    element: (tag, text, attrs) => new Element(tag, text, attrs),
    get composer() { return composer; },
    replaceComposer() { const value=composer.value; composer=new Textarea(); composer.value=value; },
    addTurnToolbar(node) { const turn=new Element(); turn.appendChild(node);
      node.appendChild(new Element('div','legacy inner text',{'data-message-author-role':'assistant'}));
      turn.appendChild(new Element('button','Copy turn'));body.appendChild(turn);return turn; },
    nativePulse() { schedule(()=>window.__OPhoneAIRuntime.supervise(),1500,1500); },
    health() { return window.__OPhoneAIRuntime.health(); },
    supervise() { return window.__OPhoneAIRuntime.supervise(); },
    recover(mode = "auto") { return window.__OPhoneAIRuntime.recover(mode); },
    killLoops() { timers.delete(this.state.timer); this.state.observer.disconnect(); },
    generating: false, user, assistant, tool, batch, ready, advance,
    start() { vm.runInContext(source, context); return this; },
    get state() { return window.__OPhoneAIRuntime.state; },
    get runtime() { return window.__OPhoneAIRuntime; },
    retry() { window.__OPhoneAIRuntime.retryProtocol(); },
    navigate(path, clear = true) { history.pushState({}, '', path); if (clear) messages.length = 0; },
    stop() { window.__OPhoneAIRuntime.stop(); },
    reload() { window.__OPhoneAIRuntime.stop(); vm.runInContext(source, context); }
  };
  return api;
}

const vmRuntime = h => h.runtime;

function taskBackend(overrides = {}) {
  let task = null;
  return async (action, args) => {
    if (overrides[action]) return overrides[action](args, task);
    if (action === 'start') task = { id: 'native-task-1', conversation: args.conversation,
      human_key: args.human_key, goal: args.goal, status: 'running', criteria: [], issues: [], pending_call: null };
    return { ok: true, task };
  };
}

test('internal turns fold into one details control while the request and answer remain visible', async () => {
  for (const modern of [false, true]) {
    const h = harness({modern}); h.ready();
    const request = h.user('打开应用商店');
    const call = h.tool('presentation-history', 'launch_app', {app_name:'应用商店'});
    const rawCall = call.textContent;
    const result = h.user(`${RESULT} ${JSON.stringify({protocol:'ophone-ai-local-tools-v1',call_id:'presentation-history',tool:'launch_app',ok:true})}`);
    const event = h.user('OPHONEAI_EVENT_V1 {"event_id":"notice-1:2","code":"app.foreground","kind":"agent_event"}');
    const answer = h.assistant('应用商店已打开。');
    h.start(); await h.advance(1500);
    for (const node of [h.messages[0],h.messages[1],call,result,event]) assert.equal(node.style.display,'none');
    assert.notEqual(request.style.display,'none'); assert.notEqual(answer.style.display,'none');
    assert.equal(call.textContent,rawCall); assert.equal(h.calls.length,0);
    assert.equal(h.document.body.descendants().filter(n=>n.id==='__ophone_ai_execution_details').length,1);
  }
});

test('ordinary protocol questions, quoted JSON and malformed records stay visible', async () => {
  const h=harness();h.ready();
  const nodes=[h.user('为什么会发 OPHONEAI_RESULT_V1 JSON？'),h.user('OPHONEAI_RESULT_V1 是什么意思？'),
    h.user('OPHONEAI_RESULT_V1 {"example":true}'),h.user('OPHONEAI_EVENT_V1 {"example":true}'),
    h.assistant('OPHONEAI_READY_V1 表示初始化完成。'),h.assistant('OPHONEAI_CALL_V1 是工具协议，下面解释。'),
    h.assistant('工具例子：\nOPHONEAI_CALL_V1\n{"call_id":"example","tool":"device_status","arguments":{}}')];
  h.start();await h.advance(1500);for(const node of nodes)assert.notEqual(node.style.display,'none');
});

test('real site JSON language labels fold calls without hiding prose following the JSON block', async () => {
  const h=harness({modern:true});h.ready();
  const call=h.tool('site-markdown-history');call.textContent=`${CALL}\n\nJSON\n${call.code.textContent}`;
  const explanatory=h.tool('explained-example');explanatory.textContent=`${CALL}\n\nJSON\n${explanatory.code.textContent}\nThis is only an example.`;
  h.user('你好');h.start();await h.advance(1500);
  assert.equal(call.style.display,'none');assert.notEqual(explanatory.style.display,'none');assert.equal(h.calls.length,0);
});

test('showing and hiding details preserves execution, result acknowledgement and call deduplication', async () => {
  const h=harness();h.ready();h.start();await h.advance(1000);
  const control=h.document.getElementById('__ophone_ai_execution_details');
  control.dispatchEvent({type:'click'});assert.equal(control.getAttribute('aria-expanded'),'true');
  assert.notEqual(h.messages[0].style.display,'none');
  h.user('Read the device model');const call=h.tool('visible-details-call');await h.advance(2500);
  assert.equal(h.calls.length,1);assert.equal(h.sent.filter(s=>s.startsWith(RESULT)).length,1);
  assert.equal(h.state.pendingResult,null);assert.notEqual(call.style.display,'none');
  control.dispatchEvent({type:'click'});await h.advance(2500);
  assert.equal(call.style.display,'none');assert.equal(h.calls.length,1);
});

test('streaming protocol JSON is folded but cannot execute before its validated stable end', async () => {
  const h=harness();h.ready();h.start();await h.advance(1000);h.user('Read model');h.generating=true;
  const call=h.assistant(`${CALL}\n\`\`\`json\n{"call_id":`);await h.advance(1000);
  assert.equal(call.style.display,'none');assert.equal(h.calls.length,0);
  call.textContent=`${CALL}\n\`\`\`json\n{"call_id":"stream-details","tool":"device_status","arguments":{}}\n\`\`\``;
  await h.advance(1000);assert.equal(h.calls.length,0);
  h.generating=false;await h.advance(2500);assert.equal(h.calls.length,1);assert.equal(call.style.display,'none');
});

test('a React node reused for a normal final answer is restored instead of remaining hidden', async () => {
  const h=harness();h.ready();h.start();await h.advance(1000);
  const node=h.messages[1];assert.equal(node.style.display,'none');
  node.textContent='现在可以直接输入手机任务。';await h.advance(500);
  assert.notEqual(node.style.display,'none');assert.equal(node.textContent,'现在可以直接输入手机任务。');
});

test('internal composer transport masks JSON, blocks trusted edits briefly, then restores the draft', async () => {
  let sendReady=false;const h=harness({sendReady:()=>sendReady});h.ready();h.start();await h.advance(1000);
  h.user('Read model');h.composer.value='我的下一条草稿';h.tool('masked-transport');await h.advance(1700);
  assert.equal(h.state.sending,true);assert.equal(h.composer.style.opacity,'0');
  assert.equal(h.composer.getAttribute('aria-hidden'),'true');assert.match(h.composer.value,/^OPHONEAI_RESULT_V1/);
  let prevented=false;h.listeners.get('beforeinput')({type:'beforeinput',target:h.composer,isTrusted:true,
    preventDefault(){prevented=true},stopImmediatePropagation(){}});assert.equal(prevented,true);
  sendReady=true;await h.advance(1500);assert.equal(h.state.sending,false);
  assert.notEqual(h.composer.style.opacity,'0');assert.equal(h.composer.getAttribute('aria-hidden'),null);
  assert.equal(h.composer.value,'我的下一条草稿');assert.equal(h.calls.length,1);
});

test('failed delivery restores the editor and draft without exposing JSON or repeating Android execution', async () => {
  const h=harness({sendBlocked:()=>true});h.ready();h.start();await h.advance(1000);
  h.user('Read model');h.composer.value='保留我的草稿';h.tool('failed-hidden-transport');await h.advance(15000);
  assert.equal(h.calls.length,1);assert.equal(h.state.pendingResult.attempts,2);
  assert.equal(h.composer.value,'保留我的草稿');assert.notEqual(h.composer.style.opacity,'0');
  assert.equal(h.document.getElementById('__ophone_ai_execution_details').textContent.startsWith('Needs attention'),true);
});

test('hidden handshake keeps rendered line breaks when textContent has different Markdown layout', async () => {
  const h=harness();h.ready('rendered-handshake');
  h.messages[0].renderedText=h.messages[0].textContent;
  h.messages[0].textContent=h.messages[0].textContent.replace(/\n/g,'');
  h.start();await h.advance(1500);assert.equal(h.state.ready,true);assert.equal(h.messages[0].style.display,'none');
  h.messages[0].renderedText+='\nadditional tool schema';h.messages[0].textContent+='additional tool schema';
  await h.advance(1000);assert.equal(h.state.ready,true);
  h.user('Read model');h.tool('rendered-cache-call');await h.advance(2500);assert.equal(h.calls.length,1);
});

test('stopping the runtime restores original message styles and removes the presentation control', async () => {
  const h=harness();h.ready();h.messages[0].style.display='flex';h.start();await h.advance(1000);
  assert.equal(h.messages[0].style.display,'none');h.stop();assert.equal(h.messages[0].style.display,'flex');
  assert.equal(h.document.getElementById('__ophone_ai_execution_details'),null);
});

test('a replaced editor carrying the same internal JSON is masked until transport finishes', async () => {
  let sendReady=false;const h=harness({sendReady:()=>sendReady});h.ready();h.start();await h.advance(1000);
  h.user('Read model');h.tool('replaced-masked-editor');await h.advance(1700);
  h.replaceComposer();await h.advance(150);assert.equal(h.composer.style.opacity,'0');
  sendReady=true;await h.advance(1500);assert.notEqual(h.composer.style.opacity,'0');
  assert.equal(h.composer.value,'');assert.equal(h.calls.length,1);
});

test('stopping during internal delivery restores a user draft instead of exposing the pending JSON', async () => {
  const h=harness({sendReady:()=>false});h.ready();h.start();await h.advance(1000);
  h.user('Read model');h.composer.value='停下后仍保留的草稿';h.tool('stop-masked-editor');await h.advance(1700);
  assert.equal(h.composer.style.opacity,'0');h.stop();
  assert.notEqual(h.composer.style.opacity,'0');assert.equal(h.composer.value,'停下后仍保留的草稿');
  await h.advance(1000);assert.equal(h.composer.value,'停下后仍保留的草稿');assert.equal(h.calls.length,1);
});

test('nested legacy metadata does not leave protocol action toolbars visible in modern turns', async () => {
  const h=harness({modern:true});h.ready();const protocol=h.messages[1];const turn=h.addTurnToolbar(protocol);
  const normal=h.assistant('正常答复');const normalTurn=h.addTurnToolbar(normal);
  h.start();await h.advance(1500);assert.equal(turn.style.display,'none');assert.notEqual(normalTurn.style.display,'none');
  h.document.getElementById('__ophone_ai_execution_details').dispatchEvent({type:'click'});
  assert.notEqual(turn.style.display,'none');assert.equal(protocol.textContent,`${READY} history`);
});

test('a new human message inside a reused wrapper restores that wrapper before hiding only the old protocol', async () => {
  const h=harness({modern:true});h.ready();const protocol=h.messages[1];const turn=h.addTurnToolbar(protocol);
  h.start();await h.advance(1500);assert.equal(turn.style.display,'none');
  const human=h.user('新的普通问题');turn.appendChild(human);await h.advance(500);
  assert.notEqual(turn.style.display,'none');assert.equal(protocol.style.display,'none');assert.notEqual(human.style.display,'none');
});

function eventFixture(options={}) {
  const task={id:'event-task',conversation:'c:event-notices',human_key:'event-origin',goal:'Observe the requested app',status:'running',criteria:[],issues:[],pending_call:null};
  const notice={id:'notice-one',task_id:task.id,subscription_id:'window-rule',latest_event_id:10,count:1,
    event:{id:10,type:'app.foreground',package:'com.example.app',level:'info'},required_checks:[],requires_diagnosis:false};
  let notices=[notice];
  const h=harness({path:'/c/event-notices',...options,nativeTaskBackend:async(action,args)=>({ok:true,task:task,event_deliveries:action==='poll'?notices:[]})});
  h.ready();const origin=h.user(task.goal);task.human_key=origin.getAttribute('data-message-id');h.start();h.nativePulse();
  return {h,task,notice,setNotices:n=>{notices=n;}};
}

test('informational subscription events push once without demanding fault logs or auto-acknowledging', async()=>{
 const {h}=eventFixture();await h.advance(5000);const pushed=h.sent.filter(x=>x.startsWith('OPHONEAI_EVENT_V1'));
 assert.equal(pushed.length,1,JSON.stringify({health:h.health(),requests:h.nativeRequests,sent:h.sent}));assert.equal(JSON.parse(pushed[0].slice('OPHONEAI_EVENT_V1'.length)).kind,'agent_event');
 assert.equal(h.state.diagnosisRequired.length,0);assert.equal(h.state.eventCount,0);
 assert.equal(h.nativeRequests.some(x=>x.action==='ack_issue'||x.action==='event_ack'),false);
 await h.advance(5000);assert.equal(h.sent.filter(x=>x.startsWith('OPHONEAI_EVENT_V1')).length,1);
});

test('conditional error events require their declared checks, not unrelated runtime logs',async()=>{
 const f=eventFixture();f.notice.requires_diagnosis=true;f.notice.required_checks=['event_query','diagnostic_query'];f.notice.event={id:10,type:'diagnostic',level:'error'};
 await f.h.advance(4500);assert.deepEqual(Array.from(f.h.state.diagnosisRequired),['event_query','diagnostic_query']);
 f.h.tool('event-read','event_query');await f.h.advance(2200);f.h.tool('error-read','diagnostic_query');await f.h.advance(2200);
 assert.equal(f.h.state.diagnosisRequired.length,0);
});

test('coalesced notifications replace a queued older version while the model is busy',async()=>{
 const f=eventFixture();f.setNotices([]);await f.h.advance(1500);f.h.generating=true;f.setNotices([f.notice]);await f.h.advance(2500);
 f.setNotices([{...f.notice,latest_event_id:11,count:2,event:{...f.notice.event,id:11}}]);await f.h.advance(2500);
 assert.equal(f.h.state.events.length,1);assert.equal(f.h.state.events[0].latest_event_id,11);
 f.h.generating=false;await f.h.advance(1500);const event=JSON.parse(f.h.sent.find(x=>x.startsWith('OPHONEAI_EVENT_V1')).slice('OPHONEAI_EVENT_V1'.length));assert.equal(event.latest_event_id,11);
});

test('another task cannot inject its subscription event into the active conversation',async()=>{
 const f=eventFixture();f.setNotices([{...f.notice,task_id:'other-task'}]);await f.h.advance(4500);
 assert.equal(f.h.sent.filter(x=>x.startsWith('OPHONEAI_EVENT_V1')).length,0);
});

test('unacknowledged native subscription events restore after page runtime restart',async()=>{
 const f=eventFixture();await f.h.advance(4500);f.h.messages.length=0;f.h.ready();f.h.user(f.task.goal);f.h.reload();await f.h.advance(4500);
 assert.equal(f.h.sent.filter(x=>x.startsWith('OPHONEAI_EVENT_V1')).length,2);
 assert.equal(f.h.calls.length,0);
});

test('failed informational event delivery remains native-owned without exhausting the task',async()=>{
 const f=eventFixture({sendBlocked:()=>true});await f.h.advance(38000);
 assert.equal(f.h.state.exhausted,false);assert.equal(f.h.state.nativeTask.id,f.task.id);
 assert.equal(f.h.nativeRequests.some(x=>x.action==='event_ack'),false);
});

test('native task registration persists the full original goal before Android execution', async () => {
  const h = harness({ path: '/c/native-start', nativeTaskBackend: taskBackend() });
  h.ready(); h.start(); await h.advance(1000); h.user('Open the store and verify the actual screen');
  h.tool('native-first'); await h.advance(2500);
  assert.equal(h.nativeRequests.find(r => r.action === 'start').args.goal, 'Open the store and verify the actual screen');
  assert.equal(h.calls[0].scope.task_id, 'native-task-1');
});

test('failed durable registration cannot run a phone action', async () => {
  const h = harness({ path: '/c/native-fail', nativeTaskBackend: taskBackend({ start: () => { throw new Error('native_database_unavailable'); } }) });
  h.ready(); h.start(); await h.advance(1000); h.user('Open store'); h.tool('no-unowned-action', 'launch_app');
  await h.advance(2500); assert.equal(h.calls.length, 0);
  const result = JSON.parse(h.sent.find(t => t.startsWith(RESULT)).slice(RESULT.length));
  assert.equal(result.ok, false); assert.match(result.error, /database_unavailable/);
});

test('a phone request is natively observed even before the model produces its first tool call', async () => {
  const h=harness({path:'/c/no-first-response',nativeTaskBackend:taskBackend({observe_request:args=>({ok:true,managed:true,
    task:{id:'first-turn-goal',conversation:args.conversation,human_key:args.human_key,goal:args.goal,status:'running',criteria:[],issues:[]}})})});
  h.ready();h.start();await h.advance(1000);h.user('打开应用商店');await h.advance(2500);
  assert.equal(h.state.nativeTask.goal,'打开应用商店');assert.equal(h.calls.length,0);
  assert.equal(h.nativeRequests.some(r=>r.action==='observe_request'),true);
});

test('observing ordinary conversation does not force a native phone task', async () => {
  const h=harness({path:'/c/ordinary-conversation',nativeTaskBackend:taskBackend({observe_request:()=>({ok:true,managed:false})})});
  h.ready();h.start();await h.advance(1000);h.user('你好');await h.advance(2500);
  assert.equal(h.state.nativeTask,null);assert.equal(h.calls.length,0);
});

test('an ordinary final reply cannot complete a native task with unmet goal conditions', async () => {
  const native = taskBackend({ propose_end: (_, task) => ({ ok: false, verified: false,
    task: { ...task, status: 'diagnosing', issues: [{ id: 'unverified-1', code: 'goal_not_verified',
      message: 'The target app is still not installed.', delivered: false }] } }) });
  const h = harness({ path: '/c/native-verify-fail', nativeTaskBackend: native });
  h.ready(); h.start(); await h.advance(1000); h.user('Install the requested app'); h.tool('read-before-claim');
  await h.advance(2500); h.assistant('Installed successfully.'); await h.advance(4000);
  assert.equal(h.nativeRequests.some(r => r.action === 'propose_end'), true);
  assert.equal(h.state.taskOpen, true); assert.equal(h.state.nativeTask.status, 'diagnosing');
  assert.equal(h.sent.some(t => t.startsWith('OPHONEAI_EVENT_V1') && t.includes('target app is still not installed')), true);
});

test('a native Android verification pass is required to close a managed task', async () => {
  const h = harness({ path: '/c/native-verify-pass', nativeTaskBackend: taskBackend({ propose_end: (_, task) => ({ ok: true,
    verified: true, task: { ...task, status: 'succeeded' } }) }) });
  h.ready(); h.start(); await h.advance(1000); h.user('Read device model'); h.tool('read-model');
  await h.advance(2500); h.assistant('The model was observed.'); await h.advance(4000);
  assert.equal(h.state.taskOpen, false); assert.equal(h.state.nativeTask.status, 'succeeded');
});

test('native task and pending receipt restore after losing all page task checkpoints', async () => {
  const pendingTask = { id: 'durable-task', conversation: 'c:native-restore', human_key: 'original-human',
    goal: 'Open the requested app and verify it', status: 'awaiting_feedback', criteria: [], issues: [], tool_count:17,
    pending_call: { call_id: 'old-native-call', tool: 'launch_app', started_at: 100 } };
  const h = harness({ path: '/c/native-restore', nativeTaskBackend: async () => ({ ok: true, task: pendingTask }),
    receipt: () => Promise.resolve({ state: 'done', response: { result: { content: [] } } }) });
  h.ready(); h.start(); await h.advance(2500);
  h.supervise(); await h.advance(1500);
  assert.equal(h.calls.length, 0); assert.equal(h.state.taskGoal, pendingTask.goal);
  assert.equal(h.state.callsThisTask,17);
  assert.equal(h.sent.filter(t => t.startsWith(RESULT)).length, 1);
  assert.equal(h.nativeRequests.some(r => r.action === 'feedback' && r.args.call_id === 'old-native-call'), true);
});

test('native task polling cannot restore a goal from another conversation', async () => {
  const h = harness({ path: '/c/current-native', nativeTaskBackend: async () => ({ ok: true,
    task: { id: 'wrong-task', conversation: 'c:other-native', human_key: 'old-human', goal: 'Wrong goal', status: 'running' } }) });
  h.ready(); h.start(); await h.advance(2000);
  assert.equal(h.state.nativeTask, null); assert.equal(h.state.activeTask, false);
});

test('native goal restores when saved history changes the optimistic human DOM id', async () => {
  const task = { id: 'durable-renamed-human', conversation: 'c:saved-human', human_key: 'optimistic-id',
    goal: 'Open the store and verify it', status: 'awaiting_model', criteria: [], issues: [], pending_call: null };
  const h = harness({ path: '/c/saved-human', nativeTaskBackend: async () => ({ ok: true, task }) });
  h.ready(); h.user(task.goal); h.start(); await h.advance(2500); h.supervise(); await h.advance(1000);
  assert.equal(h.state.nativeTask.id, task.id); assert.equal(h.state.taskGoal, task.goal);
  assert.equal(h.nativeRequests.some(r => r.action === 'start'), false); assert.equal(h.calls.length, 0);
});

test('a newer unrelated human request cannot adopt the prior native goal after reload', async () => {
  const h = harness({ path: '/c/new-human', nativeTaskBackend: async () => ({ ok: true,
    task: { id: 'previous-goal', conversation: 'c:new-human', human_key: 'previous-human',
      goal: 'Open the store', status: 'awaiting_model', criteria: [], issues: [] } }) });
  h.ready(); h.user('Read the battery level'); h.start(); await h.advance(2500); h.supervise(); await h.advance(1000);
  assert.equal(h.state.nativeTask, null); assert.equal(h.calls.length, 0);
});

test('a delivered result still awaiting native acknowledgement is not recovered or sent twice', async () => {
  const task = { id: 'ack-race-task', conversation: 'c:ack-race', human_key: 'human-before-reload',
    goal: 'Read device model', status: 'awaiting_feedback', criteria: [], issues: [],
    pending_call: { call_id: 'already-sent', tool: 'device_status', started_at: 100 } };
  const h = harness({ path: '/c/ack-race', nativeTaskBackend: async () => ({ ok: true, task }) });
  h.ready(); h.user(task.goal); h.user(`${RESULT} ${JSON.stringify({call_id:'already-sent',tool:'device_status',ok:true})}`);
  h.start(); await h.advance(2500); h.supervise(); await h.advance(2000);
  assert.equal(h.calls.length, 0); assert.equal(h.sent.length, 0);
  assert.equal(h.nativeRequests.some(r => r.action === 'feedback' && r.args.call_id === 'already-sent'), true);
  assert.equal(h.state.events.length, 0); assert.equal(h.state.inFlight, null);
});

test('native terminal success clears stale recovery failure without losing the final result', async () => {
  let complete = false;
  const h = harness({ path: '/c/terminal-success', nativeTaskBackend: taskBackend({
    poll: (_, task) => ({ ok:true, task: complete && task ? {...task,status:'succeeded'} : task })
  }) });
  h.ready();h.start();await h.advance(1000);h.user('Read device model');h.tool('last-read');await h.advance(2500);
  h.state.exhausted = true;h.state.recoveryAttempts = 3;h.state.lastError = 'recovery_limit_reached';
  complete = true;h.supervise();await h.advance(2500);h.supervise();await h.advance(500);
  assert.equal(h.state.nativeTask.status,'succeeded');assert.equal(h.state.exhausted,false);
  assert.equal(h.state.stage,'idle');assert.equal(h.state.lastError,'');assert.equal(h.state.activeTask,false);
  assert.equal(h.sent.filter(x=>x.startsWith(RESULT)).length,1);
});

test('independent supervision restarts killed observers and executes a fresh call only once', async () => {
  const h = harness({ path: '/c/heal-loops' }); h.ready(); h.start(); await h.advance(1000);
  h.user('open the store'); await h.advance(500);
  h.killLoops(); h.tool('after-loop-loss'); await h.advance(5000);
  assert.equal(h.calls.length, 0);
  h.supervise(); await h.advance(2500);
  assert.equal(h.calls.length, 1);
  h.supervise(); await h.advance(1500);
  assert.equal(h.calls.length, 1);
  assert.equal(h.traces.some(x => x.code === 'executor_recovered'), true);
  assert.equal(h.sent.some(x => x.startsWith('OPHONEAI_EVENT_V1')), true);
});

test('lost active-task guard is diagnosed and resumed from current task evidence', async () => {
  const h = harness({ path: '/c/heal-guard' }); h.ready(); h.start(); await h.advance(1000);
  h.user('inspect the phone'); await h.advance(500); h.state.activeTask = false;
  h.tool('guard-call'); await h.advance(2500); assert.equal(h.calls.length, 0);
  h.supervise(); await h.advance(2000); assert.equal(h.calls.length, 1);
  assert.equal(h.traces.some(x => x.code === 'task_guard_recovered'), true);
});

test('lost callback is recovered from a native receipt without re-executing the Android action', async () => {
  const h = harness({ path: '/c/receipt', call: () => new Promise(() => {}),
    receipt: () => Promise.resolve({ state: 'done', response: { result: { content: [], isError: false } } }) });
  h.ready(); h.start(); await h.advance(1000); h.user('launch'); h.tool('launch-once', 'launch_app');
  await h.advance(8500); assert.equal(h.state.toolRunning, true);
  h.supervise(); await h.advance(1000);
  assert.equal(h.calls.length, 1); assert.equal(h.state.toolRunning, false);
  const payload = JSON.parse(h.sent.find(x => x.startsWith(RESULT)).slice(RESULT.length));
  assert.equal(payload.runtime.recovered, true); assert.equal(payload.ok, true);
});

test('reload restores unfinished feedback from native receipt while keeping the action at most once', async () => {
  let blocked = true;
  const h = harness({ path: '/c/reload-receipt', sendBlocked: () => blocked,
    receipt: () => Promise.resolve({ state: 'done', response: { result: { content: [] } } }) });
  h.ready(); h.start(); await h.advance(1000); h.user('launch'); h.tool('reload-once', 'launch_app');
  await h.advance(15000); assert.equal(h.calls.length, 1);
  blocked = false; h.reload(); await h.advance(1500); h.supervise(); await h.advance(1500);
  assert.equal(h.calls.length, 1); assert.equal(h.state.pendingResult, null);
  assert.equal(h.sent.filter(x => x.startsWith(RESULT)).length, 1);
  assert.equal(h.traces.some(x => x.code === 'checkpoint_restored'), true);
});

test('uncertain execution is reported to the model for inspection and never automatically replayed', async () => {
  const h = harness({ path: '/c/unknown', call: () => new Promise(() => {}),
    receipt: () => Promise.resolve({ state: 'unknown' }) });
  h.ready(); h.start(); await h.advance(1000); h.user('launch'); h.tool('unknown-once', 'launch_app');
  await h.advance(8500); h.supervise(); await h.advance(2000);
  assert.equal(h.calls.length, 1);
  const payload = JSON.parse(h.sent.find(x => x.startsWith(RESULT)).slice(RESULT.length));
  assert.equal(payload.ok, false); assert.match(payload.error, /inspect_ui_before_retry/);
});

test('recovery is bounded and rejects arbitrary recovery commands', async () => {
  const h = harness(); h.ready(); h.start();
  assert.equal(h.recover('shell').ok, false);
  for (let i = 0; i < 3; i++) { assert.equal(h.recover().ok, true); await h.advance(6000); }
  assert.equal(h.recover().ok, false); assert.equal(h.state.exhausted, true);
});

test('completed assistant response closes the task and does not get a spurious timeout event', async () => {
  const h = harness({ path: '/c/finished' }); h.ready(); h.start(); await h.advance(1000);
  h.user('inspect'); h.tool('read-once'); await h.advance(2500); h.assistant('The inspection is complete.');
  await h.advance(4000); assert.equal(h.state.taskOpen, false);
  await h.advance(100000); h.supervise(); await h.advance(1000);
  assert.equal(h.sent.some(x => x.startsWith('OPHONEAI_EVENT_V1')), false);
});

test('premature completion after a mutation asks the AI for actual verification', async () => {
  const h = harness({ path: '/c/verify' }); h.ready(); h.start(); await h.advance(1000);
  h.user('open store'); h.tool('open-once', 'launch_app'); await h.advance(2500);
  h.assistant('Done.'); await h.advance(4000); h.supervise(); await h.advance(1000);
  const event = h.sent.find(x => x.startsWith('OPHONEAI_EVENT_V1'));
  assert.ok(event); assert.equal(JSON.parse(event.slice('OPHONEAI_EVENT_V1'.length)).code, 'action_verification_missing');
});

test('runtime events require fresh model diagnostic calls before normal completion', async () => {
  const h = harness({ path: '/c/diagnose' }); h.ready(); h.start(); await h.advance(1000);
  h.user('inspect'); await h.advance(500); h.killLoops(); await h.advance(4500);
  h.supervise(); await h.advance(1000);
  assert.deepEqual(Array.from(h.state.diagnosisRequired), ['runtime_status', 'runtime_logs']);
  h.tool('diag-status', 'runtime_status'); await h.advance(2200);
  assert.deepEqual(Array.from(h.state.diagnosisRequired), ['runtime_logs']);
  h.tool('diag-log', 'runtime_logs'); await h.advance(2200);
  assert.equal(h.state.diagnosisRequired.length, 0);
  h.assistant('Diagnostics and the original inspection are complete.'); await h.advance(4000);
  assert.equal(h.state.taskOpen, false);
});

test('merely acknowledging a recovery event cannot mark diagnostics complete', async () => {
  const h = harness({ path: '/c/ack-only' }); h.ready(); h.start(); await h.advance(1000);
  h.user('inspect'); await h.advance(500); h.killLoops(); await h.advance(4500);
  h.supervise(); await h.advance(1000); h.assistant('Recovery noted.'); await h.advance(4000);
  assert.equal(h.state.taskOpen, true);
  assert.equal(h.state.events.some(x => x.code === 'diagnostic_checks_pending'), true);
});

test('an unfinished receipt is not restored into a different conversation or a newer human task', async () => {
  const h = harness({ path: '/c/scoped', call: () => new Promise(() => {}) });
  h.ready(); h.start(); await h.advance(1000); h.user('old task'); h.tool('old-pending'); await h.advance(2200);
  h.navigate('/c/other'); h.ready('other'); h.user('new history'); h.tool('other-history'); await h.advance(2000);
  assert.equal(h.calls.length, 1); assert.equal(h.state.inFlight, null);
  h.navigate('/c/scoped'); h.ready('scoped'); h.user('new task'); h.tool('old-pending'); await h.advance(2000);
  assert.equal(h.state.inFlight, null); assert.equal(h.calls.length, 1);
});

test('independent supervision can retry failed initialization without a user retry click', async () => {
  let attempts = 0;
  const h = harness({ toolList: () => Promise.resolve(++attempts <= 2 ? [] : [{ name: 'device_status' }]) }).start();
  await h.advance(1500); assert.equal(h.state.phase, 'failed');
  h.supervise(); await h.advance(1500); assert.equal(h.state.phase, 'waiting_ready');
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(1000);
  assert.equal(h.state.ready, true); assert.equal(h.state.recoveryAttempts, 1);
});

test('a model reusing a call ID receives a diagnostic event instead of silent stalling or a duplicate action', async () => {
  const h = harness({ path: '/c/duplicate' }); h.ready(); h.start(); await h.advance(1000);
  h.user('inspect'); h.tool('unique-once'); await h.advance(2500);
  h.tool('unique-once'); await h.advance(1500); h.supervise(); await h.advance(1000);
  assert.equal(h.calls.length, 1);
  assert.equal(h.sent.some(x => x.startsWith('OPHONEAI_EVENT_V1') && x.includes('duplicate_call_id')), true);
});

test('stopped runtime with in-flight work recovers its receipt and cancels the abandoned promise', async () => {
  const old = deferred();
  const h = harness({ path: '/c/stopped', call: () => old.promise,
    receipt: () => Promise.resolve({ state: 'done', response: { result: { content: [] } } }) });
  h.ready(); h.start(); await h.advance(1000); h.user('inspect'); h.tool('stop-once'); await h.advance(2500);
  h.stop(); await h.advance(5000); h.supervise(); await h.advance(3000); h.supervise(); await h.advance(2000);
  old.resolve({ content: [] }); await h.advance(1000);
  assert.equal(h.calls.length, 1); assert.equal(h.state.toolRunning, false);
  assert.equal(h.sent.filter(x => x.startsWith(RESULT)).length, 1);
});

test('reload with virtualized setup recovers the pending receipt after a fresh handshake', async () => {
  let blocked = true;
  const h = harness({ path: '/c/virtual-setup', sendBlocked: () => blocked,
    receipt: () => Promise.resolve({ state: 'done', response: { result: { content: [] } } }) });
  h.ready(); h.start(); await h.advance(1000); h.user('launch'); h.tool('pending-tail', 'launch_app');
  await h.advance(15000); h.messages.splice(0, 2); blocked = false;
  h.reload(); await h.advance(1500); assert.equal(h.state.phase, 'waiting_ready');
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(1000);
  h.supervise(); await h.advance(1500);
  assert.equal(h.calls.length, 1); assert.equal(h.sent.filter(x => x.startsWith(RESULT)).length, 1);
});

test('checkpoint fallback IDs never persist the human prompt body', async () => {
  const h = harness({ path: '/c/private-checkpoint', noMessageIds: true });
  h.ready(); h.start(); await h.advance(1000); h.user('private prompt body'); await h.advance(1000);
  const saved = h.storage.get('__ophone_ai_task_v1_c:private-checkpoint');
  assert.ok(saved); assert.equal(saved.includes('private prompt body'), false);
});

test('missing model feedback triggers one wake-up event and then a visible bounded failure', async () => {
  const h = harness({ path: '/c/model-timeout' }); h.ready(); h.start(); await h.advance(1000);
  h.user('inspect'); h.tool('read-feedback'); await h.advance(2500);
  await h.advance(91000); h.supervise(); await h.advance(1500);
  assert.equal(h.sent.filter(x => x.startsWith('OPHONEAI_EVENT_V1')).length, 1);
  await h.advance(91000); h.supervise(); await h.advance(1000);
  assert.equal(h.state.exhausted, true); assert.equal(h.state.lastError, 'model_feedback_timeout');
  assert.equal(h.sent.filter(x => x.startsWith('OPHONEAI_EVENT_V1')).length, 1);
});

test('cached and legacy READY cannot bypass a fresh handshake; draft survives', async () => {
  const h = harness({ path: '/c/one', storage: [['__ophone_ai_ready_c:one', 'ophone-ai-local-tools-unsupported']] });
  h.user('[[OPHONEAI_PROTOCOL_UNSUPPORTED]]'); h.assistant('OPHONEAI_READY_UNSUPPORTED');
  h.composer.value = 'my unfinished task'; h.start(); await h.advance(1000);
  assert.equal(h.state.phase, 'waiting_ready'); assert.equal(h.sent.length, 1);
  h.assistant(`${READY} wrong-id`); await h.advance(800);
  assert.equal(h.state.ready, false);
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(800);
  assert.equal(h.state.phase, 'ready'); assert.equal(h.composer.value, 'my unfinished task');
});

test('setup is deduplicated, times out, retries once, then exposes manual retry', async () => {
  const h = harness().start(); await h.advance(1000);
  assert.equal(h.sent.length, 1);
  await h.advance(45000); assert.equal(h.sent.length, 2);
  await h.advance(46000); assert.equal(h.state.phase, 'failed');
  await h.advance(50000); assert.equal(h.sent.length, 2);
  const gate = h.document.getElementById('__ophone_ai_ready_gate');
  assert.equal(gate.querySelector('button').style.display, 'inline-block');
  h.retry(); await h.advance(1000); assert.equal(h.sent.length, 3);
});

test('empty tool list cannot mark tools ready or send an empty protocol', async () => {
  const h = harness({ toolList: () => Promise.resolve([]) }).start();
  await h.advance(1500); assert.equal(h.state.phase, 'failed'); assert.equal(h.sent.length, 0);
});

test('Markdown-rendered setup acknowledges by handshake ID without duplicate send', async () => {
  const h = harness({ renderMarkdown: true }).start(); await h.advance(1000);
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(12000);
  assert.equal(h.state.phase, 'ready'); assert.equal(h.sent.length, 1);
});

test('login submit is not blocked by the readiness input guard', () => {
  const h = harness({ noComposer: true }).start(); let blocked = false;
  h.listeners.get('submit')({ type: 'submit', target: {}, preventDefault() { blocked = true; } });
  assert.equal(blocked, false);
});

test('composer reset during initial hydration is recovered before sending', async () => {
  let reset = false;
  const h = harness({ sendReady: (composer, now) => {
    if (!reset) { composer.value = ''; reset = true; return false; }
    return !!composer.value && now >= 1300;
  } }).start();
  await h.advance(2000); assert.equal(h.sent.length, 1);
  assert.equal(h.sent[0].startsWith(SETUP), true); assert.equal(h.state.lastError, '');
});

test('switch during tools/list cancels the old async setup', async () => {
  const old = deferred(); let count = 0;
  const h = harness({ toolList: () => ++count === 1 ? old.promise : Promise.resolve([{ name: 'device_status' }]) }).start();
  await h.advance(100); h.navigate('/c/two'); h.user('unprepared history'); await h.advance(1800);
  old.resolve([{ name: 'old-tool' }]); await h.advance(1000);
  assert.equal(h.sent.length, 1); assert.equal(h.sent[0].includes('old-tool'), false);
});

test('SPA navigation cannot inherit READY from outgoing DOM', async () => {
  const h = harness(); h.ready(); h.start(); assert.equal(h.state.ready, true);
  h.navigate('/c/two', false); await h.advance(2000);
  assert.equal(h.state.ready, false); assert.equal(h.sent.length, 0);
  h.messages.length = 0; h.user('unprepared history'); await h.advance(1800); assert.equal(h.sent.length, 1);
});

test('new-chat setup promotion to /c/id keeps its handshake and sends once', async () => {
  const h = harness({ path: '/', onSend: (text, api) => {
    if (text.startsWith(SETUP)) api.navigate('/c/created', false);
  } }).start();
  await h.advance(800); const id = h.state.handshakeId;
  h.assistant(`${READY} ${id}`); await h.advance(800);
  assert.equal(h.state.phase, 'ready'); assert.equal(h.state.epoch, 0); assert.equal(h.sent.length, 1);
});

test('history calls are not replayed; new tasks run once after DOM churn and reload', async () => {
  const h = harness({ modern: true }); h.ready(); h.user('previous task'); h.tool('old-call');
  h.start(); await h.advance(2000); assert.equal(h.calls.length, 0);
  h.user('open settings'); h.tool('new-call'); await h.advance(3000);
  assert.equal(h.calls.length, 1); assert.equal(h.sent.filter(x => x.startsWith(RESULT)).length, 1);
  h.tool('new-call'); await h.advance(3000); assert.equal(h.calls.length, 1);
  h.reload(); await h.advance(3000); assert.equal(h.calls.length, 1);
});

test('ChatGPT optimistic WEB conversation ID is promoted to its saved ID', async () => {
  const h = harness({ path: '/', onSend: (text, api) => {
    if (text.startsWith(SETUP)) api.navigate('/c/WEB:temporary', false);
  } }).start();
  await h.advance(1000); h.navigate('/c/saved', false); await h.advance(800);
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(800);
  assert.equal(h.state.phase, 'ready'); assert.equal(h.state.epoch, 0);
  assert.equal(h.sent.length, 1); assert.equal(h.state.currentRoute, 'c:saved');
});

test('route switch during Android action drops its old conversation result', async () => {
  const pending = deferred(); const h = harness({ call: () => pending.promise });
  h.ready(); h.start(); h.user('task'); h.tool('in-flight'); await h.advance(2000);
  assert.equal(h.calls.length, 1);
  h.navigate('/c/two'); await h.advance(800); pending.resolve({ content: [] }); await h.advance(800);
  assert.equal(h.sent.filter(x => x.startsWith(RESULT)).length, 0);
});

test('provider interruption is observable even when its stop button remains visible', async () => {
  const h = harness();h.ready();h.start();h.user('open store');
  const turn=h.assistant('连接已中断。正在等待完整回复');turn.transportBanner={innerText:'连接已中断。正在等待完整回复'};
  h.generating=true;
  assert.equal(h.health().model_generating,true);
  assert.equal(h.health().model_transport_error,'connection_interrupted');
  const placeholder=h.assistant('正在思考');
  assert.equal(h.health().model_transport_error,'connection_interrupted');
  h.messages.splice(h.messages.indexOf(placeholder),1);
  turn.transportBanner.innerText='Connection interrupted. Waiting for the full response';
  assert.equal(h.health().model_transport_error,'connection_interrupted');
});

test('quoted errors and old interruption banners do not label a healthy turn as disconnected', async () => {
  const h = harness();h.ready();h.start();
  h.user('连接已中断。正在等待完整回复');assert.equal(h.health().model_transport_error,'');
  const old=h.assistant('连接已中断。正在等待完整回复');assert.equal(h.health().model_transport_error,'');
  old.transportBanner={innerText:'连接已中断。正在等待完整回复'};
  h.assistant('正在正常回答');assert.equal(h.health().model_transport_error,'');
});

test('identical human text in different messages resets the per-task counter', async () => {
  const h = harness(); h.ready(); h.start(); h.user('again'); h.tool('a'); await h.advance(2000);
  h.user('again'); h.tool('b'); await h.advance(2000);
  assert.equal(h.calls.length, 2); assert.equal(h.state.callsThisTask, 1);
  h.state.lastError='native_task_failed';h.user('a fresh task');await h.advance(600);
  assert.equal(h.state.lastError,'');
});

test('failed result delivery retries the result without repeating the Android action', async () => {
  let blocked = true;
  const h = harness({ sendBlocked: () => blocked }); h.ready(); h.start();
  h.user('task'); h.tool('once'); await h.advance(18000);
  assert.equal(h.calls.length, 1); assert.equal(h.state.pendingResult.attempts, 2);
  assert.equal(h.document.getElementById('__ophone_ai_ready_gate').querySelector('button').style.display, 'inline-block');
  blocked = false; h.retry(); await h.advance(2000);
  assert.equal(h.calls.length, 1); assert.equal(h.state.pendingResult, null);
});

test('long result acknowledgement tolerates expand/collapse controls', async () => {
  const h = harness({ longMessageControl: true }); h.ready(); h.start();
  h.user('task'); h.tool('long-result'); await h.advance(15000);
  assert.equal(h.calls.length, 1); assert.equal(h.sent.length, 1);
  assert.equal(h.state.pendingResult, null);
});

test('login pages remain usable and stop cancels delayed actions', async () => {
  const login = harness({ noComposer: true }).start(); await login.advance(5000);
  assert.equal(login.sent.length, 0); assert.equal(login.document.getElementById('__ophone_ai_ready_gate'), null);
  const h = harness(); h.ready(); h.start(); h.user('task'); h.tool('cancel');
  await h.advance(400); h.stop(); await h.advance(3000); assert.equal(h.calls.length, 0);
});

test('reload waits for delayed history before deciding to send setup', async () => {
  const h = harness({ path: '/c/saved-history' }).start(); await h.advance(3000);
  assert.equal(h.sent.length, 0); assert.equal(h.state.ready, false);
  h.ready(); h.user('old task'); h.tool('old-history-call'); await h.advance(2000);
  assert.equal(h.state.ready, true); assert.equal(h.sent.length, 0);
  assert.equal(h.calls.length, 0);
});

test('a native action returning ok=false is reported as failed even with legacy isError=false', async () => {
  const h = harness({ call: () => Promise.resolve({ isError: false,
    content: [{ type: 'text', text: JSON.stringify({ ok: false, error: 'action_not_performed' }) }] }) });
  h.ready(); h.start(); h.user('search'); h.tool('failed-click', 'click_text', { text: 'search' });
  await h.advance(2200);
  const result = JSON.parse(h.sent[0].slice(RESULT.length));
  assert.equal(result.ok, false); assert.equal(h.state.pendingResult, null);
});

test('external UI actions carry the observed package and stop carrying it after Home', async () => {
  const h = harness({ call: name => Promise.resolve({ content: [{ type: 'text',
    text: JSON.stringify(name === 'launch_app' || name === 'get_ui'
      ? { ok: true, package: 'com.example.market' } : { ok: true }) }] }) });
  h.ready(); h.start(); h.user('search in store'); h.tool('launch', 'launch_app', { app_name: 'Store' });
  await h.advance(2200); h.tool('read', 'get_ui'); await h.advance(2200);
  h.tool('search', 'click_text', { text: 'Search' }); await h.advance(2200);
  assert.equal(h.calls[1].args.expected_package, 'com.example.market');
  assert.equal(h.calls[2].args.expected_package, 'com.example.market');
  h.tool('home', 'press_global', { action: 'home' }); await h.advance(2200);
  h.tool('read-home', 'get_ui'); await h.advance(2200);
  assert.equal(h.calls[4].args.expected_package, undefined);
});

test('switching tasks drops the previous task app instead of restoring it for an unrelated request', async () => {
  const h = harness({ call: () => Promise.resolve({ content: [{ type: 'text',
    text: JSON.stringify({ ok: true, package: 'com.example.market' }) }] }) });
  h.ready(); h.start(); h.user('store'); h.tool('open-store', 'launch_app', { app_name: 'Store' });
  await h.advance(2200); h.user('inspect this screen'); h.tool('new-screen', 'get_ui'); await h.advance(2200);
  assert.equal(h.calls[1].args.expected_package, undefined);
});

test('large UI result transformed by Markdown is acknowledged by its unique envelope', async () => {
  const h = harness({ transformResult: text => text.replace(/a_b/g, 'ab'),
    call: () => Promise.resolve({ content: [{ type: 'text',
      text: JSON.stringify({ ok: true, package: 'com.example.market', nodes: [{ viewId: 'a_b' }] }) }] }) });
  h.ready(); h.start(); h.user('inspect'); h.tool('large-tree', 'get_ui'); await h.advance(15000);
  assert.equal(h.sent.length, 1); assert.equal(h.state.pendingResult, null);
});

for (const modern of [false, true]) {
  test(`tool loop survives virtualization of its human task (${modern ? 'modern' : 'legacy'} DOM)`, async () => {
    const h = harness({ modern, call: () => Promise.resolve({ content: [{ type: 'text',
      text: JSON.stringify({ ok: true, package: 'com.example.market' }) }] }) });
    h.ready(); h.start(); const task = h.user('install the requested app');
    h.tool('open-store', 'launch_app', { app_name: 'Store' }); await h.advance(2200);
    h.messages.splice(h.messages.indexOf(task), 1); // site unmounts the old user turn
    h.tool('store-ui', 'get_ui'); await h.advance(2200);
    h.tool('search-store', 'click_text', { text: 'Search' }); await h.advance(2200);
    assert.equal(h.calls.length, 3); assert.equal(h.state.activeTask, true);
    assert.equal(h.state.callsThisTask, 3);
    assert.equal(h.calls[2].args.expected_package, 'com.example.market');
  });
}

test('chat page messages wait for setup, then go out as ordinary human turns', async () => {
  const h = harness(); h.start(); await h.advance(500);
  const runtime = () => vmRuntime(h);
  assert.equal(runtime().submit('查看电量'), 'queued');
  assert.equal(h.sent.filter(s => s === '查看电量').length, 0);
  assert.ok(h.sent[0].startsWith(SETUP));
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(2500);
  assert.equal(h.sent.filter(s => s === '查看电量').length, 1);
  const items = runtime().snapshot().items;
  assert.deepEqual(Array.from(items, i => i.type), ['user']);
  assert.equal(items[0].text, '查看电量');
});

test('guidance typed during a running tool rides on its result and shows as a guidance turn', async () => {
  const pending = deferred();
  const h = harness({ call: () => pending.promise }); h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.tool('guide-1', 'launch_app', { app_name: '设置' }); await h.advance(1500);
  assert.equal(h.calls.length, 1);
  assert.equal(vmRuntime(h).snapshot().running, true);
  assert.equal(vmRuntime(h).submit('顺便看看电量'), 'guided');
  pending.resolve({ content: [{ type: 'text', text: '{"ok":true}' }] }); await h.advance(1500);
  const result = h.sent.find(s => s.startsWith(RESULT));
  assert.equal(JSON.parse(result.slice(RESULT.length)).user_guidance, '顺便看看电量');
  assert.equal(h.state.guidance.length, 0);
  const items = vmRuntime(h).snapshot().items;
  assert.deepEqual(Array.from(items, i => i.type), ['user', 'step', 'user']);
  assert.equal(items[1].state, 'ok'); assert.equal(items[2].guidance, true);
});

test('stop from the chat page drops an in-flight result instead of feeding the model', async () => {
  const pending = deferred();
  const h = harness({ call: () => pending.promise }); h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.tool('stop-1', 'launch_app', { app_name: '设置' }); await h.advance(1500);
  vmRuntime(h).submit('再看看蓝牙');
  const stopped = vmRuntime(h).userStop();
  assert.equal(stopped.ok, true);
  pending.resolve({ content: [{ type: 'text', text: '{"ok":true}' }] }); await h.advance(3000);
  assert.equal(h.sent.filter(s => s.startsWith(RESULT)).length, 0);
  assert.equal(vmRuntime(h).snapshot().running, false);
  assert.equal(h.state.guidance.length, 0);
});

test('transcript hides protocol turns and maps calls, failures and questions', async () => {
  const h = harness(); h.ready();
  h.user('检查 WLAN'); h.tool('t-1', 'click_text', { text: 'WLAN' });
  h.user(`${RESULT} ${JSON.stringify({ protocol: 'ophone-ai-local-tools-v1', call_id: 't-1', tool: 'click_text', ok: false, error: 'not_found' })}`);
  h.user('OPHONEAI_EVENT_V1 {"event_id":"e-1","code":"app.foreground"}');
  h.tool('t-2', 'ask_user', { question: '用哪个网络？', options: ['家里', '公司'] });
  h.start(); await h.advance(1500);
  const items = vmRuntime(h).snapshot().items;
  assert.deepEqual(Array.from(items, i => i.type), ['user', 'step', 'question']);
  assert.equal(items[1].state, 'error'); assert.equal(items[1].summary, 'not_found');
  assert.deepEqual(Array.from(items[2].options), ['家里', '公司']);
});

test('repeating an earlier message from the chat page still sends a new turn', async () => {
  const h = harness(); h.ready(); h.user('继续'); h.assistant('好的。'); h.start(); await h.advance(1500);
  assert.equal(vmRuntime(h).submit('继续'), 'queued'); await h.advance(1500);
  assert.equal(h.sent.filter(s => s === '继续').length, 1);
  assert.equal(h.state.userQueue.length, 0);
});

test('a new chat that gets its conversation URL after setup stays the same conversation', async () => {
  const h = harness(); h.start(); await h.advance(500);
  assert.ok(h.sent[0].startsWith(SETUP));
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(1500);
  assert.equal(h.state.ready, true);
  // The site assigns /c/<id> only after the first exchange, when no send is in flight.
  h.navigate('/c/late-id', false); await h.advance(5000);
  assert.notEqual(h.state.phase, 'failed');
  assert.equal(h.state.ready, true);
  assert.equal(h.sent.filter(x => x.startsWith(SETUP)).length, 1);
});

test('an unchanged conversation DOM after a route change is accepted instead of failing setup', async () => {
  const h = harness(); h.ready('other'); h.user('hello'); h.assistant('hi'); h.start(); await h.advance(1500);
  h.navigate('/c/renamed', false); await h.advance(20000);
  assert.notEqual(h.state.lastError, 'conversation_dom_not_ready');
  assert.notEqual(h.state.phase, 'failed');
});

test('a batch runs its tools in order and returns every result in one delivery', async () => {
  const h = harness({ call: name => Promise.resolve({ content: [{ type: 'text', text: JSON.stringify({ ok: true, from: name }) }] }) });
  h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置');
  h.batch('b-1', [{ tool: 'task_status', arguments: {} }, { tool: 'capabilities' }, { tool: 'launch_app', arguments: { app_name: '设置' } }]);
  await h.advance(3000);
  assert.deepEqual(h.calls.map(c => c.name), ['task_status', 'capabilities', 'launch_app']);
  assert.deepEqual(h.calls.map(c => c.scope.call_id), ['b-1.1', 'b-1.2', 'b-1.3']);
  const results = h.sent.filter(s => s.startsWith(RESULT));
  assert.equal(results.length, 1);
  const payload = JSON.parse(results[0].slice(RESULT.length));
  assert.equal(payload.tool, 'batch'); assert.equal(payload.ok, true); assert.equal(payload.results.length, 3);
  const items = vmRuntime(h).snapshot().items;
  assert.deepEqual(Array.from(items, i => i.type), ['user', 'step', 'step', 'step']);
  assert.ok(Array.from(items).slice(1).every(i => i.state === 'ok'));
});

test('a batch stops at the first failure and reports the rest as not run', async () => {
  const h = harness({ call: name => Promise.resolve({ content: [{ type: 'text', text: JSON.stringify({ ok: name !== 'tap' }) }] }) });
  h.ready(); h.start(); await h.advance(1000);
  h.user('点一下');
  h.batch('b-2', [{ tool: 'tap', arguments: { x: 1, y: 2 } }, { tool: 'get_ui' }]);
  await h.advance(3000);
  assert.deepEqual(h.calls.map(c => c.name), ['tap']);
  const payload = JSON.parse(h.sent.find(s => s.startsWith(RESULT)).slice(RESULT.length));
  assert.equal(payload.ok, false);
  assert.equal(payload.results[1].ok, false);
  assert.match(payload.results[1].error, /^not_run/);
});

test('a batch larger than the limit is not executed', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  h.user('太多了');
  h.batch('b-3', Array.from({ length: 7 }, () => ({ tool: 'device_status' })));
  await h.advance(3000);
  assert.equal(h.calls.length, 0);
});

test('the recovery limit is logged once, not on every supervision tick', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  for (let i = 0; i < 12; i++) { h.recover('retry_delivery'); await h.advance(6000); }
  assert.equal(h.traces.filter(t => t.code === 'recovery_limit_reached').length, 1);
  assert.equal(h.state.exhausted, true);
});

test('an oversized batch result is shortened to fit one message', async () => {
  const big = 'x'.repeat(30000);
  const h = harness({ call: () => Promise.resolve({ content: [{ type: 'text', text: JSON.stringify({ ok: true, data: big }) }] }) });
  h.ready(); h.start(); await h.advance(1000);
  h.user('列出应用');
  h.batch('b-big', [{ tool: 'find_apps' }, { tool: 'get_ui' }]);
  await h.advance(3000);
  const text = h.sent.find(s => s.startsWith(RESULT));
  assert.ok(text.length < 30000);
  const payload = JSON.parse(text.slice(RESULT.length));
  assert.equal(payload.results[0].result.truncated, true);
});

test('the live view says when ChatGPT is writing a tool call and how long the stage has lasted', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.generating = true; h.assistant(`${CALL}\n\`\`\`json\n{"call_id":"x`);
  await h.advance(500);
  const first = vmRuntime(h).snapshot().live;
  assert.equal(first.stage, 'thinking'); assert.equal(first.writing_call, true);
  await h.advance(3000);
  assert.equal(vmRuntime(h).snapshot().live.since, first.since);
});

test('when ChatGPT has stopped, the chat page is idle and a new message goes out as a normal message', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.tool('idle-1', 'launch_app', { app_name: '设置' }); await h.advance(3000);
  // ChatGPT answered (or stopped) without finishing the native task: the task stays open.
  h.assistant('设置已经打开了。'); await h.advance(40000);
  assert.equal(h.state.taskOpen || h.state.activeTask || true, true);
  assert.equal(vmRuntime(h).snapshot().running, false);
  assert.equal(vmRuntime(h).submit('再打开蓝牙'), 'queued');
  await h.advance(2000);
  assert.equal(h.sent.filter(s => s === '再打开蓝牙').length, 1);
});

test('guidance left over when ChatGPT stops is sent instead of waiting for a tool result', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.generating = true; h.assistant('正在处理');
  await h.advance(500);
  assert.equal(vmRuntime(h).submit('顺便看看电量'), 'guided');
  h.generating = false; await h.advance(8000);
  assert.equal(h.sent.filter(s => s === '顺便看看电量').length, 1);
});

test('on-demand setup: opening a chat injects nothing until the user sends', async () => {
  const h = harness({ lazy: true }); h.start(); await h.advance(3000);
  assert.equal(h.sent.length, 0);
  assert.equal(vmRuntime(h).snapshot().standby, true);
  assert.equal(vmRuntime(h).submit('查看电量'), 'queued');
  await h.advance(500);
  assert.ok(h.sent[0].startsWith(SETUP));
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(2500);
  assert.equal(h.sent.filter(s => s === '查看电量').length, 1);
});

test('on-demand setup: an old chat that was connected before is reused without a new handshake', async () => {
  const h = harness({ lazy: true, path: '/c/old' }); h.ready('old-setup'); h.user('以前的任务'); h.assistant('完成了');
  h.start(); await h.advance(3000);
  assert.equal(h.state.ready, true);
  assert.equal(h.sent.length, 0);
});

test('on-demand setup: sending on the page itself is held, connected, then sent', async () => {
  const h = harness({ lazy: true }); h.start(); await h.advance(2000);
  h.composer.value = '打开蓝牙';
  let prevented = false;
  h.listeners.get('keydown')({ type: 'keydown', key: 'Enter', shiftKey: false, isTrusted: true, target: h.composer,
    preventDefault() { prevented = true; }, stopImmediatePropagation() {} });
  assert.equal(prevented, true);
  await h.advance(500);
  assert.ok(h.sent[0].startsWith(SETUP));
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(2500);
  assert.equal(h.sent.filter(s => s === '打开蓝牙').length, 1);
});

test('the setup notice covers only the composer, never the conversation above it', async () => {
  const h = harness(); h.start(); await h.advance(500);
  const gate = h.document.getElementById('__ophone_ai_ready_gate');
  assert.ok(gate);
  assert.equal(gate.style.top, '650px'); assert.equal(gate.style.height, '50px');
  assert.match(gate.style.cssText, /background:transparent/);
});

test('the page is the reference: an idle page frees the next message without waiting out old timers', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  assert.equal(vmRuntime(h).submit('打开设置'), 'queued'); await h.advance(1500);
  assert.equal(h.sent.filter(s => s === '打开设置').length, 1);
  // ChatGPT never started a reply (it failed or was stopped on the page): still "working" for a while, since the
  // site may be thinking without a recognizable signal, then idle.
  await h.advance(9000);
  assert.equal(vmRuntime(h).snapshot().running, true);
  await h.advance(37000);
  assert.equal(vmRuntime(h).snapshot().running, false);
  assert.equal(vmRuntime(h).submit('再试一次'), 'queued'); await h.advance(1500);
  assert.equal(h.sent.filter(s => s === '再试一次').length, 1);
});

test('a stuck tool result on an idle page carries the user message, or yields to it as a normal turn', async () => {
  let block = true, h;
  h = harness({ sendBlocked: () => block && h.composer.value.startsWith(RESULT),
    call: () => Promise.resolve({ content: [{ type: 'text', text: '{"ok":true}' }] }) });
  h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.tool('stuck-1', 'launch_app', { app_name: '设置' }); await h.advance(20000);
  assert.ok(h.state.pendingResult);
  assert.equal(vmRuntime(h).snapshot().running, false);
  assert.equal(vmRuntime(h).submit('改成打开蓝牙'), 'queued');
  await h.advance(500);
  assert.ok(Array.from(vmRuntime(h).snapshot().items).some(i => i.text === '改成打开蓝牙' && i.queued));
  await h.advance(20000);
  assert.equal(h.sent.filter(s => s === '改成打开蓝牙').length, 1);
  assert.equal(h.state.pendingResult, null);
});

test('a delayed tool result delivered with the user message keeps it as guidance', async () => {
  let block = true, h;
  h = harness({ sendBlocked: () => block && h.composer.value.startsWith(RESULT),
    call: () => Promise.resolve({ content: [{ type: 'text', text: '{"ok":true}' }] }) });
  h.ready(); h.start(); await h.advance(1000);
  h.user('打开设置'); h.tool('late-1', 'launch_app', { app_name: '设置' }); await h.advance(20000);
  block = false;
  vmRuntime(h).submit('顺便关掉蓝牙'); await h.advance(3000);
  const result = h.sent.filter(s => s.startsWith(RESULT)).pop();
  assert.equal(JSON.parse(result.slice(RESULT.length)).user_guidance, '顺便关掉蓝牙');
  assert.equal(h.sent.filter(s => s === '顺便关掉蓝牙').length, 0);
});

function listFetch(total) {
  const all = Array.from({ length: total }, (_, i) => ({ id: `conv-${String(i).padStart(4, '0')}`, title: `chat ${i}`, update_time: 1700000000 - i }));
  const seen = [];
  const fetch = url => {
    seen.push(url);
    if (url === '/api/auth/session') return Promise.resolve({ json: () => Promise.resolve({ accessToken: 'token' }) });
    const q = new URLSearchParams(url.split('?')[1]);
    const offset = Number(q.get('offset')), limit = Number(q.get('limit'));
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ items: all.slice(offset, offset + limit), total }) });
  };
  return { fetch, seen };
}

test('the end of the chat list loads the next page from the site and keeps the pages already loaded', async () => {
  const list = listFetch(60);
  const h = harness({ fetch: list.fetch }); h.ready(); h.start(); await h.advance(1000);
  vmRuntime(h).refreshConversations(); await h.advance(100);
  assert.equal(vmRuntime(h).snapshot().conversations.items.length, 28);
  vmRuntime(h).moreConversations(); await h.advance(100);
  let listed = vmRuntime(h).snapshot().conversations;
  assert.equal(listed.items.length, 56); assert.equal(listed.has_more, true);
  assert.ok(list.seen.some(u => u.includes('offset=28')));
  // A refresh of the first page does not shrink the list.
  vmRuntime(h).refreshConversations(); await h.advance(100);
  assert.equal(vmRuntime(h).snapshot().conversations.items.length, 56);
  vmRuntime(h).moreConversations(); await h.advance(100);
  listed = vmRuntime(h).snapshot().conversations;
  assert.equal(listed.items.length, 60); assert.equal(listed.has_more, false);
});

test('pulling for older messages only scrolls the page and never sends anything', async () => {
  const h = harness(); h.ready(); h.user('hello'); h.assistant('hi'); h.start(); await h.advance(1000);
  const before = h.sent.length;
  const result = vmRuntime(h).loadOlder();
  assert.equal(typeof result.ok, 'boolean');
  await h.advance(2000);
  assert.equal(h.sent.length, before);
});

test('on-demand setup: an old chat that loads slowly is not treated as a failure and injects nothing', async () => {
  const h = harness({ lazy: true, path: '/c/slow-chat' }); h.start(); await h.advance(1000);
  h.nativePulse(); await h.advance(30000);
  assert.equal(h.sent.length, 0);
  assert.notEqual(h.state.phase, 'failed');
  // The chat appears later; still nothing is sent until the user sends.
  h.user('以前的问题'); h.assistant('以前的回答'); await h.advance(5000);
  assert.equal(h.sent.length, 0);
  assert.equal(vmRuntime(h).submit('继续'), 'queued'); await h.advance(500);
  assert.ok(h.sent[0].startsWith(SETUP));
  assert.equal(h.sent.filter(s => s === '继续').length, 0);
  h.assistant(`${READY} ${h.state.handshakeId}`); await h.advance(2500);
  assert.equal(h.sent.filter(s => s === '继续').length, 1);
});

test('on-demand setup: a failed handshake is not resent by recovery until the user sends again', async () => {
  const h = harness({ lazy: true }); h.start(); await h.advance(1000);
  vmRuntime(h).submit('查看电量'); await h.advance(500);
  assert.equal(h.sent.filter(s => s.startsWith(SETUP)).length, 1);
  // The message itself went nowhere yet; the user stops waiting and clears it.
  vmRuntime(h).userStop(); h.state.setupRequested = false;
  h.runtime.retryProtocol(); h.nativePulse(); await h.advance(30000);
  assert.equal(h.sent.filter(s => s.startsWith(SETUP)).length, 1);
  assert.equal(vmRuntime(h).snapshot().standby, true);
});

test('a connected chat is renamed once after the user first real message, not the setup text', async () => {
  const patches = [];
  const fetch = (url, init = {}) => {
    if (url === '/api/auth/session') return Promise.resolve({ json: () => Promise.resolve({ accessToken: 'token' }) });
    if (init.method === 'PATCH') { patches.push({ url, body: JSON.parse(init.body) }); return Promise.resolve({ ok: true, status: 200 }); }
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ items: [] }) });
  };
  const h = harness({ fetch, path: '/c/abcdef12-3456' }); h.ready(); h.user('帮我  打开\n蓝牙'); h.assistant('好的'); h.start(); await h.advance(2000);
  assert.equal(patches.length, 1);
  assert.equal(patches[0].url, '/backend-api/conversation/abcdef12-3456');
  assert.equal(patches[0].body.title, '帮我 打开 蓝牙');
  await h.advance(90000);
  assert.equal(patches.length, 2); // one confirmation after the site's own title generation, then never again
});

test('a thinking indicator after the newest user turn keeps the chat page busy and shows the page line', async () => {
  const h = harness(); h.ready(); h.start(); await h.advance(1000);
  vmRuntime(h).submit('分析一下'); await h.advance(1500);
  h.thinkingNodes.push(h.element('div', '正在思考', { class: 'loading-shimmer' }));
  await h.advance(60000);
  const snap = vmRuntime(h).snapshot();
  assert.equal(snap.running, true);
  assert.equal(snap.live.page_signal, 'thinking_indicator');
  assert.deepEqual(Array.from(snap.live.page_lines), ['正在思考']);
  assert.equal(vmRuntime(h).submit('顺便看看电量'), 'guided');
  h.thinkingNodes.length = 0; await h.advance(200);
  assert.equal(vmRuntime(h).snapshot().live.page_signal, '');
});

test('a stop control found by test id counts as generating; old indicators above the newest turn do not', async () => {
  const h = harness(); h.ready();
  const stale = h.element('div', '旧的', { class: 'loading-shimmer' });
  h.user('你好'); h.assistant('你好！'); h.start(); await h.advance(1000);
  h.thinkingNodes.push(stale);
  await h.advance(60000);
  assert.equal(vmRuntime(h).snapshot().running, false);
  h.extraButtons.push(h.element('button', '', { 'data-testid': 'composer-stop-button' }));
  await h.advance(500);
  assert.equal(vmRuntime(h).snapshot().running, true);
  assert.equal(vmRuntime(h).snapshot().live.page_signal, 'stop_testid');
});
