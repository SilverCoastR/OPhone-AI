/* Chat page controller. Native side: window.OPhoneChat (ApiChatActivity.Bridge).
   Native events arrive through window.OPhoneChatApp.event(json). */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var LANG = params.get('lang') === 'zh' ? 'zh' : 'en';
  // provider=web: ChatGPT runs in a hidden page and this UI shows its snapshots.
  var WEB = params.get('provider') === 'web';
  document.documentElement.dataset.theme = params.get('dark') === '1' ? 'dark' : 'light';
  document.documentElement.lang = LANG === 'zh' ? 'zh-CN' : 'en';

  var STRINGS = {
    zh: {
      newChat: '新对话', about: '关于与更新', feedback: '反馈问题', browser: '内置浏览器', model: '模型与 API Key', settings: '设置', platform: '切换平台',
      search: '搜索对话', greeting: '有什么可以帮你？', placeholder: '给 OPhone AI 发消息或布置手机任务',
      placeholderRunning: '任务进行中，可发送补充指令…', copy: '复制', copied: '已复制', guidance: '补充指令',
      thinking: '正在思考', working: '正在处理', done: '已处理', steps: '个步骤', stepsDone: '已完成 {n} 个步骤', errors: '{n} 个出错',
      failed: '步骤出错', args: '参数', result: '结果', noKey: '尚未设置 DeepSeek API Key', setKey: '去设置',
      today: '今天', yesterday: '昨天', week: '7 天内', older: '更早', noChats: '没有对话', delete: '删除', miniapps: '小应用',
      hint: '内容由 AI 生成，执行手机操作前请确认授权范围', asks: 'AI 需要你的回答', stopped: '已停止', copyChat: '复制对话',
      s1t: '查看手机状态', s1d: '电量、存储和网络情况', s1p: '帮我查看手机当前的电量、存储空间和网络连接状态',
      s2t: '用 Python 计算', s2d: '写脚本并给出结果', s2p: '用 Python 计算 2024 年每个月的天数，并用表格列出',
      s3t: '画一张流程图', s3d: 'Mermaid 图表', s3p: '用 Mermaid 画一张“下单到收货”的流程图',
      s4t: '解释一个公式', s4d: '数学公式渲染', s4p: '解释欧拉公式 $e^{i\\pi}+1=0$，并给出推导',
      connecting: '正在连接手机工具', sendingResult: '正在回传结果', verifying: '正在核对结果', notReady: '手机工具未就绪',
      webModel: 'ChatGPT 网页', webPlaceholder: '给 ChatGPT 发消息或布置手机任务', login: '尚未登录 ChatGPT', loginBtn: '登录',
      showWeb: '查看网页', retry: '重试', setupFailed: '手机工具连接失败', deliveryFailed: '工具结果没有发出去',
      update: '更新', install: '安装', downloading: '下载中', checkUpdate: '查看',
      preparingTools: '正在准备手机工具', writingCall: 'ChatGPT 正在安排下一步操作', waitingReply: '等待 ChatGPT 回复', writingAnswer: 'ChatGPT 正在输出回答 · 已输出 {n} 字', pageSays: 'ChatGPT 页面显示', webView: '网页', localView: '本地视图',
      loadFailed: '连不上 chatgpt.com（{n}）。请检查手机网络：在国内需要能访问 ChatGPT 的网络，使用代理时要对 OPhone AI 生效。', reconnect: '重新连接', autoRetry: '正在自动重试…',
      edit: '编辑', deleteMsg: '删除', cancel: '取消', resend: '发送', stepN: '第 {n} 步', usedTime: '用时 {n}', answeredBy: '由 {n} 回答',
      noKeyFor: '尚未设置 {n} API', consentTitle: '让 {to} 继续这个对话？', consentGo: '继续', consentNew: '新建对话',
      consentBody: '这个对话之前由 {from} 处理。继续后，本对话的文字记录和工具结果会发送给 {company}。API Key 和其他平台的思考过程不会发送。',
      stalled: '执行器已停止响应', sendFailed: '消息没有发出去', pageAttention: 'ChatGPT 页面需要你处理（验证或登录）', loading: '正在打开 ChatGPT…',
      loadingOlder: '正在从网页加载更早的消息…', noOlder: '没有更早的消息了', loadingChats: '正在从网页加载更多对话…', noMoreChats: '没有更多对话了',
      retrying: '正在重试…', opening: '正在打开…', errorCode: '错误代码：{n}',
      why_page_no_reply: '网页 15 秒没有回应 OPhone AI 的检查（可能在加载、在输出很长的回复，或系统暂停了后台网页）。网页恢复后提示会自动消失，任务一般会继续。',
      why_recovery_limit_reached: '自动恢复已试了 3 次仍不正常，已停止自动处理。ChatGPT 如果继续回复，工具仍会照常执行；不动了就点重试。',
      why_model_feedback_timeout: '执行结果已交给 ChatGPT，但它 90 秒没有继续，提醒后也没回应。可以点重试，或直接发消息让它继续。',
      why_diagnostic_event_limit_reached: '已提醒 ChatGPT 3 次检查问题，它仍没有继续，已停止提醒。可以发消息告诉它下一步。',
      why_diagnostic_feedback_delivery_failed: '给 ChatGPT 的状态通知两次都没能写进网页。点重试会重新发送，不会重复手机操作。',
      why_model_diagnostic_checks_missing: 'ChatGPT 没按要求先核对执行情况就结束了回答，任务可能没完成。可以发消息让它核实结果。',
      why_default: '自动处理已暂停。反馈时请附上错误代码和反馈日志（日志里有 runtime_stalled 记录）。'
    },
    en: {
      newChat: 'New chat', about: 'About & updates', feedback: 'Send feedback', browser: 'Browser', model: 'Model & API key', settings: 'Settings', platform: 'Switch platform',
      search: 'Search chats', greeting: 'What can I help with?', placeholder: 'Message OPhone AI or give the phone a task',
      placeholderRunning: 'Task running — send extra guidance…', copy: 'Copy', copied: 'Copied', guidance: 'Guidance',
      thinking: 'Thinking', working: 'Working', done: 'Done', steps: 'steps', stepsDone: 'Completed {n} steps', errors: '{n} failed',
      failed: 'Step failed', args: 'Arguments', result: 'Result', noKey: 'DeepSeek API key is not set', setKey: 'Set up',
      today: 'Today', yesterday: 'Yesterday', week: 'Previous 7 days', older: 'Older', noChats: 'No chats', delete: 'Delete', miniapps: 'Mini apps',
      hint: 'AI can make mistakes. Check what you authorize before phone actions.', asks: 'Needs your answer', stopped: 'Stopped', copyChat: 'Copy chat',
      s1t: 'Check the phone', s1d: 'battery, storage and network', s1p: 'Check the phone\'s current battery level, free storage and network connection',
      s2t: 'Calculate with Python', s2d: 'write and run a script', s2p: 'Use Python to list the number of days in each month of 2024 as a table',
      s3t: 'Draw a flowchart', s3d: 'Mermaid diagram', s3p: 'Draw a Mermaid flowchart from placing an order to delivery',
      s4t: 'Explain a formula', s4d: 'math rendering', s4p: 'Explain Euler\'s identity $e^{i\\pi}+1=0$ with a derivation',
      connecting: 'Connecting phone tools', sendingResult: 'Sending result', verifying: 'Checking the result', notReady: 'Phone tools not ready',
      webModel: 'ChatGPT web', webPlaceholder: 'Message ChatGPT or give the phone a task', login: 'Not signed in to ChatGPT', loginBtn: 'Sign in',
      showWeb: 'Open page', retry: 'Retry', setupFailed: 'Phone tools failed to connect', deliveryFailed: 'A tool result was not sent',
      update: 'Update', install: 'Install', downloading: 'Downloading', checkUpdate: 'View',
      preparingTools: 'Preparing phone tools', writingCall: 'ChatGPT is planning the next action', waitingReply: 'Waiting for ChatGPT', writingAnswer: 'ChatGPT is writing the answer · {n} characters', pageSays: 'Shown on the ChatGPT page', webView: 'Web', localView: 'Local view',
      loadFailed: 'Cannot reach chatgpt.com ({n}). Check the phone\'s network; a VPN or proxy must also apply to OPhone AI.', reconnect: 'Reconnect', autoRetry: 'Retrying automatically…',
      edit: 'Edit', deleteMsg: 'Delete', cancel: 'Cancel', resend: 'Send', stepN: 'Step {n}', usedTime: '{n} elapsed', answeredBy: 'Answered by {n}',
      noKeyFor: '{n} API is not set up', consentTitle: 'Let {to} continue this chat?', consentGo: 'Continue', consentNew: 'New chat',
      consentBody: '{from} handled this chat before. If you continue, its text and tool results will be sent to {company}. API keys and other platforms\' reasoning are not sent.',
      stalled: 'The executor stopped responding', sendFailed: 'Message was not sent', pageAttention: 'The ChatGPT page needs you (verification or sign-in)', loading: 'Opening ChatGPT…',
      loadingOlder: 'Loading earlier messages from the page…', noOlder: 'No earlier messages', loadingChats: 'Loading more chats from the page…', noMoreChats: 'No more chats',
      retrying: 'Retrying…', opening: 'Opening…', errorCode: 'Error code: {n}',
      why_page_no_reply: 'The page did not answer OPhone AI\'s check for 15 s (loading, writing a long reply, or paused in the background). This clears by itself when the page answers; the task usually continues.',
      why_recovery_limit_reached: 'Automatic recovery was tried 3 times and stopped. If ChatGPT keeps replying, tools still run; if it stalls, tap Retry.',
      why_model_feedback_timeout: 'The result reached ChatGPT, but it did not continue for 90 s, even after a reminder. Tap Retry or send a message to continue.',
      why_diagnostic_event_limit_reached: 'ChatGPT was reminded 3 times to check the problem and did not continue. Send a message with the next step.',
      why_diagnostic_feedback_delivery_failed: 'A status notice could not be written into the page twice. Retry resends it without repeating phone actions.',
      why_model_diagnostic_checks_missing: 'ChatGPT ended its answer without checking what happened as required; the task may be unfinished. Ask it to verify.',
      why_default: 'Automatic handling is paused. When reporting, include the error code and the feedback log (it has a runtime_stalled entry).'
    }
  }[LANG];
  function t(key, n) {
    var s = STRINGS[key] || key;
    if (n && typeof n === 'object') { Object.keys(n).forEach(function (k) { s = s.split('{' + k + '}').join(n[k]); }); return s; }
    return n === undefined ? s : s.replace('{n}', n);
  }

  var native = window.OPhoneChat || {
    state: function () { return '{}'; }, history: function () { return '[]'; }, conversations: function () { return '[]'; },
    send: function () { return 'error'; }, stop: function () {}, newChat: function () {}, select: function () {}, delete: function () {},
    copy: function () {}, open: function () {}, openLink: function () {}, retry: function () {}, update: function () {}
  };
  function call(name) {
    try { return native[name].apply(native, Array.prototype.slice.call(arguments, 1)); } catch (e) { return null; }
  }
  function json(text, fallback) { try { return JSON.parse(text); } catch (e) { return fallback; } }

  var $ = function (id) { return document.getElementById(id); };
  var thread = $('thread'), scroller = $('scroller'), input = $('input'), sendBtn = $('send');
  var state = { running: false, conversation: '', hasKey: true, model: '', providerLabel: '' };
  var live = null; // { turn, process, answer } for the turn currently streaming events
  var stickToBottom = true;

  var ICONS = {
    ok: '<svg viewBox="0 0 24 24"><path d="M5 12.5 10 17l9-10"/></svg>',
    error: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5h.01"/></svg>',
    chev: '<svg class="chev" viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg>',
    copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M5 12.5 10 17l9-10"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></svg>',
    edit: '<svg viewBox="0 0 24 24"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>',
    trash: '<svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
    gear: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/></svg>'
  };

  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html !== undefined) n.innerHTML = html;
    return n;
  }
  function applyI18n(root) {
    Array.prototype.forEach.call((root || document).querySelectorAll('[data-i18n]'), function (n) { n.textContent = t(n.getAttribute('data-i18n')); });
  }

  // Tool names → short readable labels; unknown tools fall back to the raw name.
  var TOOL_LABELS = {
    zh: { tap: '点击屏幕', swipe: '滑动', click_text: '点击文字', input_text: '输入文字', press_global: '系统按键', launch_app: '打开应用', get_ui: '读取界面', screenshot: '截图', find_apps: '查找应用', capabilities: '检查能力', task_status: '任务状态', task_checkpoint: '保存进度', task_verify: '验证结果', task_finish: '完成任务', task_wait: '等待事件', event_subscribe: '订阅事件', browser_open: '打开网页', browser_snapshot: '读取网页', browser_action: '操作网页', browser_eval: '运行网页脚本', browser_screenshot: '网页截图', terminal_start: '运行命令', terminal_status: '读取命令输出', python_run: '运行 Python', http_request: 'HTTP 请求', workbench_file: '读写文件', workbench_status: '检查工作台', open_url: '打开链接', download_file: '下载文件', program_run: '运行小工具', program_save: '保存小工具', wait_for_ui: '等待界面', ask_user: '询问你', memory_search: '查找经验', memory_save: '保存经验', memory_delete: '删除记忆', android_intent: '调用系统功能', android_resolve: '查询应用能力', android_info: '读取系统状态', android_control: '系统控制', miniapp_save: '保存小应用', miniapp_open: '打开小应用', miniapp_read: '读取小应用', miniapp_list: '列出小应用', miniapp_delete: '删除小应用' },
    en: { tap: 'Tap', swipe: 'Swipe', click_text: 'Tap text', input_text: 'Type text', press_global: 'System key', launch_app: 'Open app', get_ui: 'Read screen', screenshot: 'Screenshot', find_apps: 'Find apps', capabilities: 'Check capabilities', task_status: 'Task status', task_checkpoint: 'Save progress', task_verify: 'Verify result', task_finish: 'Finish task', task_wait: 'Wait for event', event_subscribe: 'Subscribe events', browser_open: 'Open page', browser_snapshot: 'Read page', browser_action: 'Act on page', browser_eval: 'Run page script', browser_screenshot: 'Page screenshot', terminal_start: 'Run command', terminal_status: 'Read command output', python_run: 'Run Python', http_request: 'HTTP request', workbench_file: 'Files', workbench_status: 'Check workbench', open_url: 'Open link', download_file: 'Download', program_run: 'Run utility', program_save: 'Save utility', wait_for_ui: 'Wait for screen', ask_user: 'Ask you', memory_search: 'Recall', memory_save: 'Remember', memory_delete: 'Forget', android_intent: 'System action', android_resolve: 'App capabilities', android_info: 'System state', android_control: 'Device control', miniapp_save: 'Save mini app', miniapp_open: 'Open mini app', miniapp_read: 'Read mini app', miniapp_list: 'List mini apps', miniapp_delete: 'Delete mini app' }
  }[LANG];
  function toolLabel(name) { return TOOL_LABELS[name] || name; }
  // One-line hint from tool arguments: the most descriptive field, trimmed.
  function argsHint(args) {
    var a = json(args, null);
    if (!a || typeof a !== 'object') return '';
    var keys = ['text', 'url', 'command', 'package', 'app', 'query', 'path', 'name', 'action', 'goal', 'summary', 'code', 'script'];
    for (var i = 0; i < keys.length; i++) {
      var v = a[keys[i]];
      if (typeof v === 'string' && v.trim()) return v.replace(/\s+/g, ' ').slice(0, 80);
    }
    return '';
  }
  function pretty(text) {
    var v = json(text, null);
    return v === null ? String(text || '') : JSON.stringify(v, null, 2);
  }

  /* ---------- rendering ---------- */

  function newTurn(userText, guidance, detached, id) {
    var turn = el('div', 'turn');
    if (userText !== undefined) {
      var row = el('div', 'user-row'), msg = el('div', 'user-msg' + (guidance ? ' guidance' : ''));
      msg.textContent = userText;
      if (guidance) msg.setAttribute('data-label', t('guidance'));
      row.appendChild(msg);
      turn.appendChild(row);
      turn.appendChild(messageActions(turn, userText, id));
      turn._userId = id || '';
      msg.addEventListener('click', function () { turn.classList.toggle('show-actions'); });
    }
    var body = el('div', 'assistant');
    turn.appendChild(body);
    turn._body = body;
    if (!detached) thread.appendChild(turn);
    return turn;
  }

  // Copy for every message; edit and delete where the platform's context belongs to this app (API platforms).
  function messageActions(turn, text, id) {
    var bar = el('div', 'msg-actions');
    var copy = el('button', 'press', ICONS.copy + '<span></span>');
    copy.lastChild.textContent = t('copy');
    copy.addEventListener('click', function (e) { e.stopPropagation(); call('copy', text); copy.lastChild.textContent = t('copied'); setTimeout(function () { copy.lastChild.textContent = t('copy'); }, 1500); });
    bar.appendChild(copy);
    if (id && !WEB) {
      var edit = el('button', 'press', ICONS.edit + '<span></span>');
      edit.lastChild.textContent = t('edit');
      edit.addEventListener('click', function (e) { e.stopPropagation(); startEdit(turn, text, id); });
      var del = el('button', 'press danger', ICONS.trash + '<span></span>');
      del.lastChild.textContent = t('deleteMsg');
      del.addEventListener('click', function (e) { e.stopPropagation(); turn.classList.remove('show-actions'); call('deleteMessage', id); });
      bar.appendChild(edit);
      bar.appendChild(del);
    }
    return bar;
  }
  // Edit in place like ChatGPT: everything after this message is replaced by the new reply.
  function startEdit(turn, text, id) {
    if (state.running) return;
    turn.classList.remove('show-actions');
    turn.classList.add('editing');
    var box = el('div', 'edit-box glass');
    box.innerHTML = '<textarea rows="3"></textarea><div class="edit-buttons"><button class="cancel press"></button><button class="go press"></button></div>';
    var area = box.querySelector('textarea');
    area.value = text;
    box.querySelector('.cancel').textContent = t('cancel');
    box.querySelector('.go').textContent = t('resend');
    box.querySelector('.cancel').addEventListener('click', function () { box.remove(); turn.classList.remove('editing'); });
    box.querySelector('.go').addEventListener('click', function () {
      var next = area.value.trim();
      if (!next) return;
      var result = call('editMessage', id, next);
      if (consentNeeded(result, function () { box.querySelector('.go').click(); })) return;
      if (result !== 'sent') return;
      while (turn.nextElementSibling) thread.removeChild(turn.nextElementSibling);
      thread.removeChild(turn);
      beginTurn(next);
    });
    turn.insertBefore(box, turn.firstChild.nextSibling);
    area.focus();
    area.setSelectionRange(area.value.length, area.value.length);
  }
  // Another API platform answered before: ask once before this chat's history goes to the current one.
  function consentNeeded(result, retry) {
    var request = typeof result === 'string' && result.charAt(0) === '{' ? json(result, null) : null;
    if (!request || !request.consent) return false;
    var c = request.consent, sheet = el('div', 'sheet-wrap');
    sheet.innerHTML = '<div class="sheet glass"><h3></h3><p></p><button class="go press"></button><button class="alt press"></button><button class="cancel press"></button></div>';
    sheet.querySelector('h3').textContent = t('consentTitle', c);
    sheet.querySelector('p').textContent = t('consentBody', c);
    sheet.querySelector('.go').textContent = t('consentGo');
    sheet.querySelector('.alt').textContent = t('consentNew');
    sheet.querySelector('.cancel').textContent = t('cancel');
    function close() { sheet.remove(); }
    sheet.querySelector('.go').addEventListener('click', function () { close(); if (call('allowHandoff') === 'ok') retry(); });
    sheet.querySelector('.alt').addEventListener('click', function () { close(); var text = input.value; call('newChat'); setTimeout(function () { if (text.trim()) submit(text); }, 400); });
    sheet.querySelector('.cancel').addEventListener('click', close);
    sheet.addEventListener('click', function (e) { if (e.target === sheet) close(); });
    document.body.appendChild(sheet);
    return true;
  }

  function processCard(turn) {
    if (turn._process) return turn._process;
    var card = el('div', 'process');
    card.innerHTML = '<button class="process-head"><span class="dot"></span><span class="label"></span><span class="elapsed"></span>' + ICONS.chev + '</button><div class="process-sub" hidden></div><div class="steps"></div>';
    card._steps = card.querySelector('.steps');
    card._label = card.querySelector('.label');
    card._dot = card.querySelector('.dot');
    card._elapsed = card.querySelector('.elapsed');
    card._sub = card.querySelector('.process-sub');
    card._count = 0;
    card._errors = 0;
    card.querySelector('.process-head').addEventListener('click', function () { card.classList.toggle('open'); });
    // The process card always sits above the answers of the same turn.
    turn._body.insertBefore(card, turn._body.firstChild);
    turn._process = card;
    return card;
  }
  // While the AI works the card stays visible: current action, step count, elapsed time and its latest note.
  // The individual steps stay folded until the card is opened.
  function setProcessLive(card, text, start) {
    card.classList.add('live');
    // While the AI works the card sits under the newest reply text, where the next output will appear.
    var body = card.parentNode;
    if (body && body.lastChild !== card) body.appendChild(card);
    if (!card._start) card._start = start || Date.now();
    if (!card._dot.querySelector('.spinner')) card._dot.innerHTML = '<span class="spinner"></span>';
    card._label.textContent = text;
    tickElapsed(card);
  }
  function clock(ms) {
    var sec = Math.max(0, Math.round(ms / 1000)), m = Math.floor(sec / 60), h = Math.floor(m / 60);
    return (h ? h + ':' + ('0' + (m % 60)).slice(-2) : m) + ':' + ('0' + (sec % 60)).slice(-2);
  }
  function tickElapsed(card) {
    if (!card._start) return;
    var parts = [];
    if (card._count) parts.push(t('stepN', card._count));
    parts.push(clock(Date.now() - card._start));
    card._elapsed.textContent = parts.join(' · ');
  }
  setInterval(function () {
    Array.prototype.forEach.call(thread.querySelectorAll('.process.live'), function (card) { tickElapsed(card); if (card._liveInfo) renderLive(card, card._liveInfo); });
  }, 1000);
  function settleProcess(card) {
    if (!card) return;
    card.classList.remove('live');
    // Done: the summary goes back to the top of the turn, above the answer it led to.
    var body = card.parentNode;
    if (body && body.firstChild !== card) body.insertBefore(card, body.firstChild);
    var pending = card.querySelectorAll('.step.pending');
    Array.prototype.forEach.call(pending, function (s) { s.classList.remove('pending'); });
    card._dot.innerHTML = card._errors ? ICONS.error : ICONS.gear;
    card._dot.style.color = card._errors ? 'var(--err)' : '';
    card._label.textContent = (card._count ? t('stepsDone', card._count) : t('done')) + (card._errors ? ' · ' + t('errors', card._errors) : '');
    var end = card._end || (card._start ? Date.now() : 0);
    card._elapsed.textContent = card._start && end - card._start >= 1000 ? t('usedTime', clock(end - card._start)) : '';
    card._sub.hidden = true;
    if (!card._count && !card._steps.children.length) card.remove();
  }

  function addStep(card, item) {
    var step = el('div', 'step ' + (item.state || 'pending'));
    var hint = argsHint(item.args);
    step.innerHTML = '<button class="row"><span class="icon"></span><span class="name"></span><span class="meta"></span></button>' +
      '<div class="detail"><div class="k">' + t('args') + '</div><pre class="args"></pre><div class="k res-k" hidden>' + t('result') + '</div><pre class="res" hidden></pre></div>';
    step.querySelector('.name').textContent = toolLabel(item.name);
    step.querySelector('.meta').textContent = hint;
    step.querySelector('.args').textContent = pretty(item.args) || '{}';
    step.querySelector('.row').addEventListener('click', function () { step.classList.toggle('open'); });
    step._name = item.name;
    setStepState(step, item.state || 'pending', item.summary);
    card._steps.appendChild(step);
    card._count++;
    return step;
  }
  function setStepState(step, st, summary) {
    step.classList.remove('pending', 'ok', 'error');
    step.classList.add(st);
    step.querySelector('.icon').innerHTML = st === 'pending' ? '<span class="spinner"></span>' : st === 'ok' ? ICONS.ok : ICONS.error;
    if (summary) {
      var res = step.querySelector('.res');
      res.textContent = pretty(summary);
      res.hidden = false;
      step.querySelector('.res-k').hidden = false;
    }
  }
  function addNote(card, text, narration) {
    var n = el('div', 'note' + (narration ? ' narration' : ''));
    n.textContent = text;
    card._steps.appendChild(n);
    // The newest note doubles as the visible "what I'm doing" line of a live card.
    card._sub.textContent = text.replace(/\s+/g, ' ').slice(0, 160);
    card._sub.hidden = !card.classList.contains('live');
  }

  function addAnswer(turn, text, streaming, item) {
    var wrap = el('div', 'answer-wrap');
    var answer = el('div', 'answer md');
    answer.innerHTML = window.OPhoneMarkdown.render(text);
    applyI18n(answer);
    var actions = el('div', 'actions');
    var copy = el('button', '', ICONS.copy);
    copy.setAttribute('aria-label', t('copy'));
    copy.addEventListener('click', function () {
      call('copy', text);
      copy.innerHTML = ICONS.check;
      setTimeout(function () { copy.innerHTML = ICONS.copy; }, 1500);
    });
    actions.appendChild(copy);
    if (item && item.id && !WEB) {
      var del = el('button', '', ICONS.trash);
      del.setAttribute('aria-label', t('deleteMsg'));
      del.addEventListener('click', function () { if (!state.running) call('deleteMessage', item.id); });
      actions.appendChild(del);
    }
    // After a platform switch, older answers say which platform wrote them.
    if (item && item.by && state.providerLabel && item.by !== state.providerLabel) {
      var by = el('span', 'by');
      by.textContent = t('answeredBy', item.by);
      actions.appendChild(by);
    }
    wrap.appendChild(answer);
    wrap.appendChild(actions);
    var liveCard = turn._process && turn._process.classList.contains('live') ? turn._process : null;
    if (liveCard && liveCard.parentNode === turn._body) turn._body.insertBefore(wrap, liveCard); else turn._body.appendChild(wrap);
    // Diagrams render once the text stops changing; half-streamed Mermaid only produces errors.
    if (!streaming) window.OPhoneMarkdown.enhance(answer);
    return answer;
  }
  // ask_user: the question with quick-answer buttons; buttons work only while it is the latest item.
  function addQuestion(turn, text, options, keepProcess) {
    if (!keepProcess) settleProcess(turn._process);
    var card = el('div', 'question-card glass');
    card.innerHTML = '<div class="q-label"></div><div class="q-text md"></div><div class="q-options"></div>';
    card.querySelector('.q-label').textContent = t('asks');
    card.querySelector('.q-text').innerHTML = window.OPhoneMarkdown.render(text);
    (options || []).forEach(function (o) {
      var b = el('button', 'q-option press');
      b.textContent = o;
      b.addEventListener('click', function () {
        if (card.classList.contains('answered')) return;
        card.classList.add('answered');
        b.classList.add('chosen');
        submit(o);
      });
      card.querySelector('.q-options').appendChild(b);
    });
    turn._body.appendChild(card);
    return card;
  }
  function closeQuestions() {
    Array.prototype.forEach.call(thread.querySelectorAll('.question-card:not(.answered)'), function (c) { c.classList.add('answered'); });
  }

  function addError(turn, text) {
    var e = el('div', 'error-card');
    e.textContent = text;
    turn._body.appendChild(e);
  }

  function renderHistory() {
    if (WEB) { thread.innerHTML = ''; applySnapshot(json(call('history'), null), true); return; }
    thread.innerHTML = '';
    live = null;
    var items = json(call('history'), []) || [];
    var turn = null;
    items.forEach(function (item) {
      if (item.type === 'user' && !item.guidance) { settleProcess(turn && turn._process); closeQuestions(); turn = newTurn(item.text, false, false, item.id); turn._t0 = item.at || 0; return; }
      if (turn && item.at && turn._process) turn._process._end = item.at;
      if (!turn) turn = newTurn();
      if (item.type === 'user') {
        var g = el('div', 'user-row'), m = el('div', 'user-msg guidance');
        m.textContent = item.text;
        m.setAttribute('data-label', t('guidance'));
        g.appendChild(m);
        turn._body.appendChild(g);
      } else if (item.type === 'answer') addAnswer(turn, item.text, false, item);
      else if (item.type === 'step') { var c = processCard(turn), s = addStep(c, item); if (item.state === 'error') c._errors++; if (!c._start && turn._t0) c._start = turn._t0; if (item.at) c._end = item.at; }
      else if (item.type === 'question') { closeQuestions(); addQuestion(turn, item.text, item.options); }
      else if (item.type === 'note') addNote(processCard(turn), item.text, !item.progress);
    });
    if (turn && turn._process) {
      if (state.running) { live = { turn: turn }; turn._process._end = 0; setProcessLive(turn._process, t('working'), turn._t0); }
      else settleProcess(turn._process);
    } else if (turn && state.running) {
      live = { turn: turn };
      setProcessLive(processCard(turn), t('working'));
    }
    updateEmpty();
    scrollToBottom(true);
  }

  function updateEmpty() {
    var empty = !thread.children.length;
    $('empty').hidden = !empty;
    thread.hidden = empty;
  }

  /* ---------- ChatGPT web mode: the native side pushes whole snapshots of the provider page ---------- */

  function activityLabel(a) {
    if (!a) return t('working');
    if (a.indexOf('tool:') === 0) return toolLabel(a.slice(5));
    return t({ setup: 'connecting', thinking: 'thinking', sending: 'sendingResult', verifying: 'verifying', failed: 'notReady' }[a] || 'working');
  }
  function groupTurns(items) {
    var turns = [], turn = null;
    items.forEach(function (item) {
      if (item.type === 'user' && !item.guidance) { turn = { user: item.text, items: [] }; turns.push(turn); return; }
      if (!turn) { turn = { items: [] }; turns.push(turn); }
      turn.items.push(item);
    });
    return turns;
  }
  // Builds one turn's DOM from snapshot data. Open process/step panels survive a rebuild.
  function buildTurn(data, isLive, old) {
    var turn = newTurn(data.user, false, true);
    var prevStart = old && old._process ? old._process._start : 0;
    data.items.forEach(function (item) {
      if (item.type === 'user') {
        var g = el('div', 'user-row'), m = el('div', 'user-msg guidance' + (item.queued ? ' queued' : ''));
        m.textContent = item.text;
        m.setAttribute('data-label', t('guidance'));
        g.appendChild(m);
        turn._body.appendChild(g);
      } else if (item.type === 'answer') addAnswer(turn, item.text, isLive);
      else if (item.type === 'step') { var c = processCard(turn), s = addStep(c, item); if (item.state === 'error') c._errors++; }
      else if (item.type === 'question') addQuestion(turn, item.text, item.options, true);
      else if (item.type === 'note') addNote(processCard(turn), item.text, true);
    });
    if (turn._process && prevStart) turn._process._start = prevStart;
    if (isLive) setProcessLive(processCard(turn), activityLabel(snap.activity), prevStart);
    else settleProcess(turn._process);
    if (old) {
      if (old._process && old._process.classList.contains('open') && turn._process) turn._process.classList.add('open');
      var before = old.querySelectorAll('.step'), after = turn.querySelectorAll('.step');
      for (var i = 0; i < before.length && i < after.length; i++) if (before[i].classList.contains('open')) after[i].classList.add('open');
      if (old.querySelector('.question-card.answered') && turn.querySelector('.question-card')) turn.querySelector('.question-card').classList.add('answered');
    }
    return turn;
  }
  var snap = { items: [], running: false, activity: '' };
  // The live card's detail: the stage and how long it has lasted, plus the status lines chatgpt.com itself shows.
  function stageText(info) {
    if (!info) return '';
    var secs = info.since ? Math.max(0, Math.round((Date.now() - info.since) / 1000)) : 0;
    var label = info.writing_call ? t('writingCall') : info.chars ? t('writingAnswer', info.chars)
      : info.stage === 'thinking' ? t('waitingReply') : activityLabel(info.stage);
    return label + (secs ? ' · ' + secs + 's' : '');
  }
  function renderLive(card, info) {
    if (!card || !info) return;
    card._liveInfo = info;
    var lines = info.page_lines || [];
    card._sub.textContent = lines.length ? lines[lines.length - 1] : stageText(info);
    card._sub.hidden = false;
    var box = card._liveBox;
    if (!box) { box = card._liveBox = el('div', 'live-notes'); }
    if (box.parentNode !== card._steps) card._steps.appendChild(box);
    box.innerHTML = '';
    if (lines.length) {
      var head = el('div', 'k');
      head.textContent = t('pageSays');
      box.appendChild(head);
      lines.forEach(function (line) { var n = el('div', 'note narration'); n.textContent = line; box.appendChild(n); });
    }
    var now = el('div', 'note');
    now.textContent = stageText(info);
    box.appendChild(now);
  }
  function applySnapshot(next, force) {
    // Turns inserted above (older history from the page) keep the reading position; growth below does not move it.
    var fromBottom = !force && !stickToBottom ? scroller.scrollHeight - scroller.scrollTop : -1, firstSig = thread.firstElementChild && thread.firstElementChild._sig;
    snap = next || snap;
    state.running = !!snap.running;
    var turns = groupTurns(snap.items || []), last = turns.length - 1;
    turns.forEach(function (data, i) {
      var isLive = i === last && state.running;
      var sig = JSON.stringify(data) + (isLive ? '|live' : '');
      var old = thread.children[i];
      if (old && old._sig === sig && !force) {
        if (isLive && old._process) { old._process._label.textContent = activityLabel(snap.activity); renderLive(old._process, snap.live); }
        return;
      }
      var turn = buildTurn(data, isLive, old);
      turn._sig = sig;
      if (old) thread.replaceChild(turn, old); else thread.appendChild(turn);
      if (isLive && turn._process) renderLive(turn._process, snap.live);
    });
    while (thread.children.length > turns.length) thread.removeChild(thread.lastElementChild);
    // A question stays answerable only while it is the latest thing in the chat.
    var questions = thread.querySelectorAll('.question-card');
    Array.prototype.forEach.call(questions, function (q, i) {
      var lastItem = (snap.items || [])[(snap.items || []).length - 1];
      if (i < questions.length - 1 || state.running || !lastItem || lastItem.type !== 'question') q.classList.add('answered');
    });
    live = state.running && thread.lastElementChild ? { turn: thread.lastElementChild } : null;
    updateEmpty();
    syncComposer();
    if (fromBottom >= 0 && firstSig && thread.firstElementChild && thread.firstElementChild._sig !== firstSig) scroller.scrollTop = scroller.scrollHeight - fromBottom;
    olderArrived();
    scrollToBottom(force);
  }

  /* ---------- state & composer ---------- */

  function refreshState() {
    var s = json(call('state'), {}) || {};
    state.running = !!s.running;
    state.conversation = s.conversation || '';
    state.hasKey = s.has_key !== false;
    state.model = s.model || '';
    state.providerLabel = s.provider_label || '';
    syncUpdate(s.update || { available: !!s.update_available });
    $('model').textContent = WEB ? 'ChatGPT' : state.model ? state.model.replace(/^deepseek-/, 'DeepSeek ') : (state.providerLabel || 'DeepSeek');
    var banner = $('banner');
    if (WEB) { webBanner(banner, s); syncComposer(); return; }
    if (!state.hasKey) {
      banner.innerHTML = '<span></span><button></button>';
      banner.firstChild.textContent = state.providerLabel ? t('noKeyFor', state.providerLabel) : t('noKey');
      banner.lastChild.textContent = t('setKey');
      banner.lastChild.onclick = function () { call('open', 'model'); };
      banner.hidden = false;
    } else banner.hidden = true;
    syncComposer();
  }
  // Sign-in, page verification and executor faults: one banner with the action that fixes it.
  function webBanner(banner, s) {
    var text = '', code = '', why = '', buttons = [], progress = null;
    if (s.load_error) { text = t('loadFailed', s.load_error) + (s.load_retrying ? ' ' + t('autoRetry') : ''); buttons = ['reconnect', 'showWeb']; }
    else if (s.page_attention) { text = t('pageAttention'); buttons = ['showWeb']; }
    else if (s.logged_in === false) { text = t('login'); buttons = ['loginBtn']; }
    else if (s.attention) {
      text = t({ setup_failed: 'setupFailed', delivery_failed: 'deliveryFailed', runtime_stalled: 'stalled', send_failed: 'sendFailed' }[s.attention] || 'notReady');
      // The runtime's own error code makes a report actionable ("send_button_not_ready", "ready_timeout"...).
      code = s.last_error || '';
      if (s.attention === 'runtime_stalled') why = STRINGS['why_' + code] || t('why_default');
      buttons = s.can_retry ? ['retry', 'showWeb'] : ['showWeb'];
    } else if (s.loading) { progress = { label: t('loading') }; }
    // The tool handshake with ChatGPT takes a few seconds: shown at the bottom, above the input, not at the top.
    else if (s.setup && !s.setup.failed) { progress = { label: t('preparingTools'), value: s.setup.value || 0, step: s.setup.step || '' }; }
    setupBar(progress);
    if (!text) { banner.hidden = true; banner._sig = ''; return; }
    // Rebuilt only when its content changes: a banner redrawn on every state poll swallows taps.
    var sig = text + '|' + code + '|' + why + '|' + buttons.join(',');
    if (banner._sig !== sig) {
      banner._sig = sig;
      banner.innerHTML = '<div class="msg"><span class="text"></span><span class="why"></span><span class="code"></span></div><div class="actions"></div>';
      banner.querySelector('.text').textContent = text;
      banner.querySelector('.why').textContent = why;
      banner.querySelector('.why').hidden = !why;
      banner.querySelector('.code').textContent = code ? t('errorCode', code) : '';
      banner.querySelector('.code').hidden = !code;
      buttons.forEach(function (name, i) {
        var b = el('button', 'press' + (i ? ' secondary' : ''));
        b.textContent = t(name);
        b.onclick = function () {
          // Visible acknowledgement, and no double taps while the action runs.
          b.disabled = true;
          b.textContent = name === 'showWeb' || name === 'loginBtn' ? t('opening') : t('retrying');
          setTimeout(function () { b.disabled = false; b.textContent = t(name); }, 4000);
          if (name === 'retry') call('retry'); else if (name === 'reconnect') call('reload'); else call('open', 'web');
        };
        banner.lastChild.appendChild(b);
      });
    }
    banner.hidden = false;
  }
  function setupBar(p) {
    var bar = $('setup-bar');
    document.body.classList.toggle('setup-on', !!p);
    if (!p) { bar.hidden = true; return; }
    bar.querySelector('.label').textContent = p.label;
    var hasValue = typeof p.value === 'number';
    bar.querySelector('.track').hidden = !hasValue;
    if (hasValue) bar.querySelector('.fill').style.width = Math.round(p.value * 100) + '%';
    bar.querySelector('.step').textContent = p.step || '';
    bar.querySelector('.step').hidden = !p.step;
    bar.hidden = false;
  }
  // Update available: a dot on the menu button and on "About & updates", plus a one-tap update button.
  function syncUpdate(u) {
    var available = !!u.available;
    $('menu-dot').hidden = !available;
    $('update-dot').hidden = !available;
    var btn = $('update-btn');
    btn.hidden = !available;
    if (!available) return;
    var downloading = u.state === 'downloading';
    btn.textContent = downloading ? (u.progress >= 0 ? u.progress + '%' : t('downloading')) : u.state === 'downloaded' ? t('install') : t('update');
    btn.classList.toggle('busy', downloading);
  }
  $('update-btn').addEventListener('click', function (e) {
    e.stopPropagation();
    if (this.classList.contains('busy')) return;
    call('update');
  });
  function syncComposer() {
    input.placeholder = state.running ? t('placeholderRunning') : WEB ? t('webPlaceholder') : t('placeholder');
    var hasText = input.value.trim().length > 0;
    sendBtn.classList.toggle('running', state.running && !hasText);
    sendBtn.disabled = !state.running && !hasText;
  }
  function autosize() {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 168) + 'px';
  }

  function submit(text) {
    text = (text === undefined ? input.value : text).trim();
    if (!text) return;
    var guided = state.running;
    var result = call('send', text);
    if (consentNeeded(result, function () { submit(text); })) return;
    if (WEB) {
      // The next snapshot shows the message; nothing is drawn optimistically here.
      if (result !== 'sent' && result !== 'guided') return;
      input.value = '';
      autosize();
      closeQuestions();
      stickToBottom = true;
      return;
    }
    if (result === 'busy' || result === 'empty' || result === 'error' || result === null) { if (result === 'error') refreshState(); return; }
    input.value = '';
    autosize();
    closeQuestions();
    if (guided || result === 'guided') {
      var turn = live ? live.turn : thread.lastElementChild ? thread.lastElementChild : newTurn();
      var g = el('div', 'user-row'), m = el('div', 'user-msg guidance');
      m.textContent = text;
      m.setAttribute('data-label', t('guidance'));
      g.appendChild(m);
      turn._body.appendChild(g);
    } else beginTurn(text);
    updateEmpty();
    syncComposer();
    scrollToBottom(true);
  }
  function beginTurn(text) {
    if (live) settleProcess(live.turn._process);
    var nt = newTurn(text);
    live = { turn: nt };
    setProcessLive(processCard(nt), t('thinking'));
    state.running = true;
    updateEmpty();
    syncComposer();
    scrollToBottom(true);
  }

  /* ---------- native events ---------- */

  function ensureLive() {
    if (!live) {
      var last = thread.lastElementChild;
      live = { turn: last && last._body ? last : newTurn() };
      updateEmpty();
    }
    return live;
  }
  function onEvent(e) {
    switch (e.type) {
      case 'response_start': {
        var l = ensureLive();
        state.running = true;
        setProcessLive(processCard(l.turn), t('thinking'));
        break;
      }
      case 'status': {
        if (live && live.turn._process && live.turn._process.classList.contains('live')) live.turn._process._label.textContent = e.text;
        break;
      }
      case 'tool': {
        var lt = ensureLive(), card = processCard(lt.turn);
        lt.lastStep = addStep(card, { name: e.name, args: e.args, state: 'pending' });
        setProcessLive(card, toolLabel(e.name) + (argsHint(e.args) ? ' · ' + argsHint(e.args) : ''));
        break;
      }
      case 'tool_result': {
        if (!live) break;
        var c2 = processCard(live.turn), pending = c2.querySelectorAll('.step.pending'), step = null;
        for (var i = pending.length - 1; i >= 0; i--) if (pending[i]._name === e.name) { step = pending[i]; break; }
        if (step) setStepState(step, e.ok ? 'ok' : 'error', e.summary);
        if (!e.ok) c2._errors++;
        break;
      }
      case 'progress': {
        if (e.kind === 'tool' || e.kind === 'step') break;
        addNote(processCard(ensureLive().turn), e.text, false);
        break;
      }
      case 'text': {
        var la = ensureLive();
        addAnswer(la.turn, e.text);
        break;
      }
      case 'question': {
        var lq = ensureLive();
        addQuestion(lq.turn, e.text, json(e.options, []));
        break;
      }
      case 'error': {
        var le = ensureLive();
        settleProcess(le.turn._process);
        addError(le.turn, e.text);
        finishLive();
        break;
      }
      case 'finished':
        if (live) settleProcess(live.turn._process);
        finishLive();
        if (!WEB) {
          var keep = stickToBottom ? -1 : scroller.scrollTop;
          renderHistory();
          if (keep >= 0) scroller.scrollTop = keep;
        }
        break;
      case 'snapshot':
        applySnapshot(e.snapshot, false);
        refreshState();
        return;
      case 'reload':
        refreshState();
        renderHistory();
        renderDrawer();
        closeDrawer();
        return;
      case 'conversations':
        renderDrawer();
        return;
      case 'state':
        var was = state.running;
        refreshState();
        if (was !== state.running) renderHistory();
        return;
    }
    scrollToBottom(false);
  }
  function finishLive() {
    live = null;
    state.running = false;
    refreshState();
    if (document.body.classList.contains('drawer-open')) renderDrawer();
  }

  /* ---------- scrolling ---------- */

  function nearBottom() { return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80; }
  function scrollToBottom(force) {
    if (!force && !stickToBottom) return;
    requestAnimationFrame(function () { scroller.scrollTop = scroller.scrollHeight; });
  }
  /* ---------- web: pull at the top loads older turns on chatgpt.com, which reach this page with the snapshot ---------- */

  var older = { at: 0, count: 0, timer: 0 }, lastTop = 0, pullStart = -1;
  function pullNote(text) {
    var note = $('pull-note');
    if (!note) { note = el('div', 'pull-note'); note.id = 'pull-note'; scroller.insertBefore(note, thread); }
    note.textContent = text || '';
    note.hidden = !text;
  }
  function loadOlder() {
    if (!WEB || !thread.children.length || Date.now() - older.at < 2500) return;
    older.at = Date.now(); older.count = ((snap && snap.items) || []).length;
    pullNote(t('loadingOlder'));
    call('loadOlder');
    clearTimeout(older.timer);
    older.timer = setTimeout(function () {
      if (((snap && snap.items) || []).length <= older.count) { pullNote(t('noOlder')); older.timer = setTimeout(function () { pullNote(''); }, 2500); }
    }, 5000);
  }
  function olderArrived() {
    if (older.at && ((snap && snap.items) || []).length > older.count) { older.at = 0; clearTimeout(older.timer); pullNote(''); }
  }
  scroller.addEventListener('touchstart', function (e) { pullStart = scroller.scrollTop <= 0 && e.touches[0] ? e.touches[0].clientY : -1; }, { passive: true });
  scroller.addEventListener('touchmove', function (e) {
    if (pullStart >= 0 && e.touches[0] && e.touches[0].clientY - pullStart > 60) { pullStart = -1; loadOlder(); }
  }, { passive: true });

  scroller.addEventListener('scroll', function () {
    if (WEB && scroller.scrollTop < 40 && scroller.scrollTop < lastTop) loadOlder();
    lastTop = scroller.scrollTop;
    stickToBottom = nearBottom();
    $('jump').hidden = stickToBottom || $('empty').hidden === false;
  }, { passive: true });
  $('jump').addEventListener('click', function () { stickToBottom = true; scrollToBottom(true); });

  /* ---------- drawer ---------- */

  function bucket(ts) {
    var now = new Date(), d = new Date(ts || 0);
    var start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    if (d.getTime() >= start) return 'today';
    if (d.getTime() >= start - 864e5) return 'yesterday';
    if (d.getTime() >= start - 7 * 864e5) return 'week';
    return 'older';
  }
  var moreChats = { at: 0, count: 0, note: '' };
  function loadMoreChats() {
    if (!WEB || $('search').value.trim() || Date.now() - moreChats.at < 4000) return;
    moreChats.at = Date.now(); moreChats.count = (json(call('conversations'), []) || []).length; moreChats.note = t('loadingChats');
    renderDrawer();
    call('moreConversations');
    setTimeout(function () {
      var now = (json(call('conversations'), []) || []).length;
      moreChats.note = now > moreChats.count ? '' : t('noMoreChats');
      if (document.body.classList.contains('drawer-open')) renderDrawer();
    }, 6000);
  }
  $('history').addEventListener('scroll', function () {
    var list = $('history');
    if (list.scrollHeight - list.scrollTop - list.clientHeight < 80) loadMoreChats();
  }, { passive: true });
  function renderDrawer() {
    var list = $('history'), q = $('search').value.trim().toLowerCase();
    var all = json(call('conversations'), []) || [];
    var keepScroll = list.scrollTop;
    if (moreChats.note === t('loadingChats') && all.length > moreChats.count) moreChats.note = '';
    list.innerHTML = '';
    var groups = {}, order = ['today', 'yesterday', 'week', 'older'];
    all.forEach(function (c) {
      if (q && String(c.title || '').toLowerCase().indexOf(q) < 0) return;
      var b = bucket(c.updated_at);
      (groups[b] = groups[b] || []).push(c);
    });
    var any = false;
    var apps = json(call('miniapps'), []) || [];
    if (apps.length && !q) {
      var ah = el('h6');
      ah.textContent = t('miniapps');
      list.appendChild(ah);
      apps.forEach(function (a) {
        var row = el('div', 'conv');
        row.innerHTML = '<button class="open"></button>';
        row.firstChild.textContent = '\u25A6  ' + (a.title || a.name);
        row.firstChild.addEventListener('click', function () { closeDrawer(); call('openMiniapp', a.name); });
        list.appendChild(row);
      });
    }
    order.forEach(function (b) {
      if (!groups[b]) return;
      any = true;
      var h = el('h6');
      h.textContent = t(b);
      list.appendChild(h);
      groups[b].forEach(function (c) {
        var row = el('div', 'conv' + (c.id === state.conversation ? ' active' : ''));
        row.innerHTML = '<button class="open"></button>' + (c.id === state.conversation && state.running ? '<span class="running"></span>' : '') + (WEB ? '' : '<button class="more" aria-label="' + t('delete') + '">' + ICONS.more + '</button>');
        row.querySelector('.open').textContent = c.title || t('newChat');
        row.querySelector('.open').addEventListener('click', function () {
          if (c.id === state.conversation) { closeDrawer(); return; }
          call('select', c.id);
        });
        if (!WEB) row.querySelector('.more').addEventListener('click', function () { call('delete', c.id); });
        list.appendChild(row);
      });
    });
    if (!any) {
      var none = el('div', 'none');
      none.textContent = t('noChats');
      list.appendChild(none);
    }
    if (WEB && moreChats.note && !q) {
      var more = el('div', 'none');
      more.textContent = moreChats.note;
      list.appendChild(more);
    }
    list.scrollTop = keepScroll;
  }
  function openDrawer() {
    refreshState();
    renderDrawer();
    $('scrim').hidden = false;
    requestAnimationFrame(function () { document.body.classList.add('drawer-open'); });
    input.blur();
  }
  function closeDrawer() {
    document.body.classList.remove('drawer-open');
    setTimeout(function () { if (!document.body.classList.contains('drawer-open')) $('scrim').hidden = true; }, 400);
  }
  $('menu').addEventListener('click', openDrawer);
  $('title').addEventListener('click', openDrawer);
  $('scrim').addEventListener('click', closeDrawer);
  $('search').addEventListener('input', renderDrawer);
  $('search').placeholder = t('search');
  function startNewChat() { call('newChat'); }
  $('new').addEventListener('click', startNewChat);
  $('drawer-new').addEventListener('click', startNewChat);
  Array.prototype.forEach.call(document.querySelectorAll('[data-open]'), function (b) {
    b.addEventListener('click', function () { closeDrawer(); call('open', b.getAttribute('data-open')); });
  });
  // Swipe from the left edge opens the drawer, swipe left closes it.
  var touchX = null, touchY = null;
  document.addEventListener('touchstart', function (e) { touchX = e.touches[0].clientX; touchY = e.touches[0].clientY; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (touchX === null) return;
    var dx = e.changedTouches[0].clientX - touchX, dy = Math.abs(e.changedTouches[0].clientY - touchY);
    var open = document.body.classList.contains('drawer-open');
    if (!open && touchX < 28 && dx > 60 && dy < 50) openDrawer();
    if (open && dx < -60 && dy < 50) closeDrawer();
    touchX = null;
  }, { passive: true });

  /* ---------- composer & content interactions ---------- */

  input.addEventListener('input', function () { autosize(); syncComposer(); });
  sendBtn.addEventListener('click', function () {
    if (state.running && !input.value.trim()) {
      call('stop');
      if (live && live.turn._process) live.turn._process._label.textContent = t('stopped');
      return;
    }
    submit();
  });
  thread.addEventListener('click', function (e) {
    var copyBtn = e.target.closest('.copy-code');
    if (copyBtn) {
      var code = copyBtn.closest('.codeblock').querySelector('code').textContent;
      call('copy', code);
      var label = copyBtn.querySelector('span');
      label.textContent = t('copied');
      setTimeout(function () { label.textContent = t('copy'); }, 1500);
      return;
    }
    var a = e.target.closest('a[href]');
    if (a) {
      e.preventDefault();
      call('openLink', a.href);
    }
  });

  var suggestions = $('suggestions');
  [1, 2, 3, 4].forEach(function (i) {
    var b = el('button', 'glass press');
    b.innerHTML = '<b></b><span></span>';
    b.firstChild.textContent = t('s' + i + 't');
    b.lastChild.textContent = t('s' + i + 'd');
    b.addEventListener('click', function () { submit(t('s' + i + 'p')); });
    suggestions.appendChild(b);
  });
  $('hint').textContent = t('hint');

  window.OPhoneChatApp = {
    event: function (raw) { var e = typeof raw === 'string' ? json(raw, null) : raw; if (e) onEvent(e); },
    back: function () { if (document.body.classList.contains('drawer-open')) { closeDrawer(); return true; } return false; }
  };

  applyI18n();
  if (WEB) {
    var modelItem = document.querySelector('[data-open="model"] span'); if (modelItem) modelItem.textContent = t('webModel');
    // One tap to the chatgpt.com page; the same running conversation and tools continue there.
    var viewWeb = $('view-web');
    viewWeb.hidden = false;
    viewWeb.lastChild.textContent = t('webView');
    viewWeb.setAttribute('aria-label', t('webView'));
    viewWeb.addEventListener('click', function () { call('open', 'web'); });
  }
  refreshState();
  renderHistory();
})();
