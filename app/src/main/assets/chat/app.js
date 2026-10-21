/* Chat page controller. Native side: window.OPhoneChat (ApiChatActivity.Bridge).
   Native events arrive through window.OPhoneChatApp.event(json). */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  // The phone's language: Chinese, Japanese, Korean, otherwise English.
  var LANG = ['zh', 'ja', 'ko'].indexOf(params.get('lang')) >= 0 ? params.get('lang') : 'en';
  // provider=web: ChatGPT runs in a hidden page and this UI shows its snapshots.
  var WEB = params.get('provider') === 'web';
  document.documentElement.dataset.theme = params.get('dark') === '1' ? 'dark' : 'light';
  if (WEB) document.documentElement.classList.add('web');
  document.documentElement.lang = { zh: 'zh-CN', ja: 'ja', ko: 'ko' }[LANG] || 'en';

  var STRINGS = {
    zh: {
      tools: '内置工具', share: '分享', schedules: '定时任务', newChat: '新对话', about: '关于与更新', feedback: '反馈问题', browser: '内置浏览器', model: '模型与 API Key', settings: '设置', platform: '切换平台',
      search: '搜索对话', greeting: '有什么可以帮你？', placeholder: '给 OPhone AI 发消息或布置手机任务',
      placeholderRunning: '任务进行中，可发送补充指令…', copy: '复制', copied: '已复制', guidance: '补充指令',
      thinking: '正在思考', working: '正在处理', done: '已处理', steps: '个步骤', stepsDone: '已完成 {n} 个步骤', errors: '{n} 个出错',
      failed: '步骤出错', args: '参数', result: '结果', noKey: '尚未设置 DeepSeek API Key', setKey: '去设置',
      today: '今天', yesterday: '昨天', week: '7 天内', older: '更早', noChats: '没有对话', delete: '删除', miniapps: '小应用',
      hint: '内容由 AI 生成，执行手机操作前请确认授权范围', asks: 'AI 需要你的回答', stopped: '已停止', copyChat: '复制对话',
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
      chooseModel: '选择模型', chooseEffort: '选择思考强度', modelLoading: '正在读取网页版的选项…', modelFailed: '无法读取或切换网页版的选项', switching: '正在切换…',
      why_page_no_reply: '网页 15 秒没有回应 OPhone AI 的检查（可能在加载、在输出很长的回复，或系统暂停了后台网页）。网页恢复后提示会自动消失，任务一般会继续。',
      why_recovery_limit_reached: '自动恢复已试了 3 次仍不正常，已停止自动处理。ChatGPT 如果继续回复，工具仍会照常执行；不动了就点重试。',
      why_model_feedback_timeout: '执行结果已交给 ChatGPT，但它 90 秒没有继续，提醒后也没回应。可以点重试，或直接发消息让它继续。',
      why_diagnostic_event_limit_reached: '已提醒 ChatGPT 3 次检查问题，它仍没有继续，已停止提醒。可以发消息告诉它下一步。',
      why_diagnostic_feedback_delivery_failed: '给 ChatGPT 的状态通知两次都没能写进网页。点重试会重新发送，不会重复手机操作。',
      why_model_diagnostic_checks_missing: 'ChatGPT 没按要求先核对执行情况就结束了回答，任务可能没完成。可以发消息让它核实结果。',
      why_default: '自动处理已暂停。反馈时请附上错误代码和反馈日志（日志里有 runtime_stalled 记录）。'
    },
    en: {
      tools: 'Built-in tools', share: 'Share', schedules: 'Scheduled tasks', newChat: 'New chat', about: 'About & updates', feedback: 'Send feedback', browser: 'Browser', model: 'Model & API key', settings: 'Settings', platform: 'Switch platform',
      search: 'Search chats', greeting: 'What can I help with?', placeholder: 'Message OPhone AI or give the phone a task',
      placeholderRunning: 'Task running — send extra guidance…', copy: 'Copy', copied: 'Copied', guidance: 'Guidance',
      thinking: 'Thinking', working: 'Working', done: 'Done', steps: 'steps', stepsDone: 'Completed {n} steps', errors: '{n} failed',
      failed: 'Step failed', args: 'Arguments', result: 'Result', noKey: 'DeepSeek API key is not set', setKey: 'Set up',
      today: 'Today', yesterday: 'Yesterday', week: 'Previous 7 days', older: 'Older', noChats: 'No chats', delete: 'Delete', miniapps: 'Mini apps',
      hint: 'AI can make mistakes. Check what you authorize before phone actions.', asks: 'Needs your answer', stopped: 'Stopped', copyChat: 'Copy chat',
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
      chooseModel: 'Choose model', chooseEffort: 'Choose reasoning level', modelLoading: 'Reading the options on the ChatGPT page…', modelFailed: 'Could not read or change the option on the ChatGPT page', switching: 'Switching…',
      why_page_no_reply: 'The page did not answer OPhone AI\'s check for 15 s (loading, writing a long reply, or paused in the background). This clears by itself when the page answers; the task usually continues.',
      why_recovery_limit_reached: 'Automatic recovery was tried 3 times and stopped. If ChatGPT keeps replying, tools still run; if it stalls, tap Retry.',
      why_model_feedback_timeout: 'The result reached ChatGPT, but it did not continue for 90 s, even after a reminder. Tap Retry or send a message to continue.',
      why_diagnostic_event_limit_reached: 'ChatGPT was reminded 3 times to check the problem and did not continue. Send a message with the next step.',
      why_diagnostic_feedback_delivery_failed: 'A status notice could not be written into the page twice. Retry resends it without repeating phone actions.',
      why_model_diagnostic_checks_missing: 'ChatGPT ended its answer without checking what happened as required; the task may be unfinished. Ask it to verify.',
      why_default: 'Automatic handling is paused. When reporting, include the error code and the feedback log (it has a runtime_stalled entry).'
    },
    ja: {
      tools: '内蔵ツール', share: '共有', schedules: 'スケジュール', newChat: '新しいチャット', about: '情報とアップデート', feedback: 'フィードバック', browser: 'ブラウザ', model: 'モデルと API キー', settings: '設定', platform: 'プラットフォーム切替',
      search: 'チャットを検索', greeting: '何をお手伝いしましょうか？', placeholder: 'OPhone AI にメッセージ、またはスマホのタスクを指示',
      placeholderRunning: 'タスク実行中 — 追加の指示を送信…', copy: 'コピー', copied: 'コピーしました', guidance: '追加の指示',
      thinking: '考え中', working: '処理中', done: '完了', steps: 'ステップ', stepsDone: '{n} ステップ完了', errors: '{n} 件失敗',
      failed: 'ステップ失敗', args: '引数', result: '結果', noKey: 'DeepSeek API キーが未設定です', setKey: '設定する',
      today: '今日', yesterday: '昨日', week: '過去 7 日間', older: 'それ以前', noChats: 'チャットはありません', delete: '削除', miniapps: 'ミニアプリ',
      hint: 'AI は間違えることがあります。スマホ操作を許可する前に内容を確認してください。', asks: '回答が必要です', stopped: '停止しました', copyChat: 'チャットをコピー',
      connecting: 'スマホツールに接続中', sendingResult: '結果を送信中', verifying: '結果を確認中', notReady: 'スマホツールの準備ができていません',
      webModel: 'ChatGPT ウェブ', webPlaceholder: 'ChatGPT にメッセージ、またはスマホのタスクを指示', login: 'ChatGPT にログインしていません', loginBtn: 'ログイン',
      showWeb: 'ページを開く', retry: '再試行', setupFailed: 'スマホツールの接続に失敗しました', deliveryFailed: 'ツールの結果を送信できませんでした',
      update: 'アップデート', install: 'インストール', downloading: 'ダウンロード中', checkUpdate: '見る',
      preparingTools: 'スマホツールを準備中', writingCall: 'ChatGPT が次の操作を計画中', waitingReply: 'ChatGPT を待っています', writingAnswer: 'ChatGPT が回答を作成中 · {n} 文字', pageSays: 'ChatGPT ページの表示', webView: 'ウェブ', localView: 'ローカル表示',
      loadFailed: 'chatgpt.com に接続できません（{n}）。スマホのネットワークを確認してください。VPN やプロキシは OPhone AI にも適用されている必要があります。', reconnect: '再接続', autoRetry: '自動で再試行しています…',
      edit: '編集', deleteMsg: '削除', cancel: 'キャンセル', resend: '送信', stepN: 'ステップ {n}', usedTime: '経過 {n}', answeredBy: '{n} が回答',
      noKeyFor: '{n} の API が未設定です', consentTitle: '{to} でこのチャットを続けますか？', consentGo: '続ける', consentNew: '新しいチャット',
      consentBody: 'このチャットは以前 {from} が担当していました。続けると、本文とツールの結果が {company} に送信されます。API キーや他のプラットフォームの推論内容は送信されません。',
      stalled: '実行エンジンが応答しなくなりました', sendFailed: 'メッセージを送信できませんでした', pageAttention: 'ChatGPT ページで操作が必要です（認証またはログイン）', loading: 'ChatGPT を開いています…',
      loadingOlder: 'ページから以前のメッセージを読み込み中…', noOlder: 'これより前のメッセージはありません', loadingChats: 'ページからチャットをさらに読み込み中…', noMoreChats: 'これ以上チャットはありません',
      retrying: '再試行中…', opening: '開いています…', errorCode: 'エラーコード：{n}',
      chooseModel: 'モデルを選択', chooseEffort: '推論の強さを選択', modelLoading: 'ChatGPT ページの選択肢を読み込み中…', modelFailed: 'ChatGPT ページの選択肢を読み込めないか、切り替えられませんでした', switching: '切り替え中…',
      why_page_no_reply: 'ページが OPhone AI の確認に 15 秒間応答しませんでした（読み込み中、長い回答を作成中、またはバックグラウンドで一時停止）。ページが応答すれば自動で解消され、通常タスクは続行します。',
      why_recovery_limit_reached: '自動復旧を 3 回試して停止しました。ChatGPT が応答を続けていればツールは動作します。止まっている場合は「再試行」をタップしてください。',
      why_model_feedback_timeout: '結果は ChatGPT に届きましたが、リマインド後も 90 秒間続行しませんでした。「再試行」をタップするか、メッセージを送って続けてください。',
      why_diagnostic_event_limit_reached: 'ChatGPT に問題の確認を 3 回促しましたが続行しませんでした。次の手順をメッセージで送ってください。',
      why_diagnostic_feedback_delivery_failed: '状態の通知を 2 回ページに書き込めませんでした。再試行すると、スマホ操作を繰り返さずに再送します。',
      why_model_diagnostic_checks_missing: 'ChatGPT は必要な確認をせずに回答を終えました。タスクが未完了の可能性があります。確認するよう依頼してください。',
      why_default: '自動処理を一時停止しています。報告の際はエラーコードとフィードバックログ（runtime_stalled の記録を含む）を添えてください。'
    },
    ko: {
      tools: '내장 도구', share: '공유', schedules: '예약 작업', newChat: '새 채팅', about: '정보 및 업데이트', feedback: '피드백 보내기', browser: '브라우저', model: '모델 및 API 키', settings: '설정', platform: '플랫폼 전환',
      search: '채팅 검색', greeting: '무엇을 도와드릴까요?', placeholder: 'OPhone AI에게 메시지를 보내거나 휴대폰 작업을 맡기세요',
      placeholderRunning: '작업 진행 중 — 추가 지시 보내기…', copy: '복사', copied: '복사됨', guidance: '추가 지시',
      thinking: '생각 중', working: '처리 중', done: '완료', steps: '단계', stepsDone: '{n}단계 완료', errors: '{n}개 실패',
      failed: '단계 실패', args: '인수', result: '결과', noKey: 'DeepSeek API 키가 설정되지 않았습니다', setKey: '설정하기',
      today: '오늘', yesterday: '어제', week: '지난 7일', older: '이전', noChats: '채팅 없음', delete: '삭제', miniapps: '미니 앱',
      hint: 'AI는 실수할 수 있습니다. 휴대폰 작업을 허용하기 전에 내용을 확인하세요.', asks: '답변이 필요합니다', stopped: '중지됨', copyChat: '채팅 복사',
      connecting: '휴대폰 도구 연결 중', sendingResult: '결과 전송 중', verifying: '결과 확인 중', notReady: '휴대폰 도구가 준비되지 않았습니다',
      webModel: 'ChatGPT 웹', webPlaceholder: 'ChatGPT에게 메시지를 보내거나 휴대폰 작업을 맡기세요', login: 'ChatGPT에 로그인되어 있지 않습니다', loginBtn: '로그인',
      showWeb: '페이지 열기', retry: '다시 시도', setupFailed: '휴대폰 도구 연결에 실패했습니다', deliveryFailed: '도구 결과를 보내지 못했습니다',
      update: '업데이트', install: '설치', downloading: '다운로드 중', checkUpdate: '보기',
      preparingTools: '휴대폰 도구 준비 중', writingCall: 'ChatGPT가 다음 작업을 계획 중', waitingReply: 'ChatGPT를 기다리는 중', writingAnswer: 'ChatGPT가 답변 작성 중 · {n}자', pageSays: 'ChatGPT 페이지 표시', webView: '웹', localView: '로컬 보기',
      loadFailed: 'chatgpt.com에 연결할 수 없습니다({n}). 휴대폰 네트워크를 확인하세요. VPN이나 프록시도 OPhone AI에 적용되어야 합니다.', reconnect: '다시 연결', autoRetry: '자동으로 다시 시도하는 중…',
      edit: '편집', deleteMsg: '삭제', cancel: '취소', resend: '보내기', stepN: '{n}단계', usedTime: '{n} 경과', answeredBy: '{n}의 답변',
      noKeyFor: '{n} API가 설정되지 않았습니다', consentTitle: '{to}(으)로 이 채팅을 계속할까요?', consentGo: '계속', consentNew: '새 채팅',
      consentBody: '이 채팅은 이전에 {from}이(가) 처리했습니다. 계속하면 대화 내용과 도구 결과가 {company}(으)로 전송됩니다. API 키와 다른 플랫폼의 추론 내용은 전송되지 않습니다.',
      stalled: '실행기가 응답하지 않습니다', sendFailed: '메시지를 보내지 못했습니다', pageAttention: 'ChatGPT 페이지에서 확인이 필요합니다(인증 또는 로그인)', loading: 'ChatGPT 여는 중…',
      loadingOlder: '페이지에서 이전 메시지를 불러오는 중…', noOlder: '이전 메시지가 없습니다', loadingChats: '페이지에서 채팅을 더 불러오는 중…', noMoreChats: '더 이상 채팅이 없습니다',
      retrying: '다시 시도하는 중…', opening: '여는 중…', errorCode: '오류 코드: {n}',
      chooseModel: '모델 선택', chooseEffort: '추론 강도 선택', modelLoading: 'ChatGPT 페이지의 옵션을 읽는 중…', modelFailed: 'ChatGPT 페이지의 옵션을 읽거나 바꾸지 못했습니다', switching: '전환 중…',
      why_page_no_reply: '페이지가 OPhone AI의 확인에 15초 동안 응답하지 않았습니다(로딩 중, 긴 답변 작성 중, 또는 백그라운드에서 일시 정지). 페이지가 응답하면 자동으로 해제되며 작업은 보통 계속됩니다.',
      why_recovery_limit_reached: '자동 복구를 3번 시도한 뒤 중단했습니다. ChatGPT가 계속 응답하면 도구는 계속 실행됩니다. 멈춰 있다면 다시 시도를 누르세요.',
      why_model_feedback_timeout: '결과는 ChatGPT에 전달됐지만 알림 후에도 90초 동안 진행하지 않았습니다. 다시 시도를 누르거나 메시지를 보내 계속하세요.',
      why_diagnostic_event_limit_reached: 'ChatGPT에 문제 확인을 3번 요청했지만 진행하지 않았습니다. 다음 단계를 메시지로 보내 주세요.',
      why_diagnostic_feedback_delivery_failed: '상태 알림을 페이지에 두 번 쓰지 못했습니다. 다시 시도하면 휴대폰 작업을 반복하지 않고 다시 보냅니다.',
      why_model_diagnostic_checks_missing: 'ChatGPT가 필요한 확인 없이 답변을 끝냈습니다. 작업이 끝나지 않았을 수 있으니 확인을 요청하세요.',
      why_default: '자동 처리가 일시 중지되었습니다. 신고할 때 오류 코드와 피드백 로그(runtime_stalled 항목 포함)를 함께 보내 주세요.'
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
    ja: { tap: 'タップ', swipe: 'スワイプ', click_text: '文字をタップ', input_text: '文字を入力', press_global: 'システムキー', launch_app: 'アプリを開く', get_ui: '画面を読み取る', screenshot: 'スクリーンショット', find_apps: 'アプリを探す', capabilities: '機能を確認', task_status: 'タスク状況', task_checkpoint: '進捗を保存', task_verify: '結果を検証', task_finish: 'タスク完了', task_wait: 'イベント待ち', event_subscribe: 'イベントを購読', browser_open: 'ページを開く', browser_snapshot: 'ページを読む', browser_action: 'ページを操作', browser_eval: 'ページのスクリプト実行', browser_screenshot: 'ページのスクショ', terminal_start: 'コマンド実行', terminal_status: 'コマンド出力を読む', python_run: 'Python 実行', http_request: 'HTTP リクエスト', workbench_file: 'ファイル', workbench_status: 'ワークベンチ確認', open_url: 'リンクを開く', download_file: 'ダウンロード', program_run: 'ツール実行', program_save: 'ツール保存', wait_for_ui: '画面を待つ', ask_user: 'あなたに質問', memory_search: '記憶を検索', memory_save: '記憶する', memory_delete: '記憶を削除', android_intent: 'システム機能', android_resolve: 'アプリ機能の確認', android_info: 'システム状態', android_control: 'デバイス操作', miniapp_save: 'ミニアプリ保存', miniapp_open: 'ミニアプリを開く', miniapp_read: 'ミニアプリを読む', miniapp_list: 'ミニアプリ一覧', miniapp_delete: 'ミニアプリ削除' },
    ko: { tap: '탭', swipe: '스와이프', click_text: '텍스트 탭', input_text: '텍스트 입력', press_global: '시스템 키', launch_app: '앱 열기', get_ui: '화면 읽기', screenshot: '스크린샷', find_apps: '앱 찾기', capabilities: '기능 확인', task_status: '작업 상태', task_checkpoint: '진행 저장', task_verify: '결과 검증', task_finish: '작업 완료', task_wait: '이벤트 대기', event_subscribe: '이벤트 구독', browser_open: '페이지 열기', browser_snapshot: '페이지 읽기', browser_action: '페이지 조작', browser_eval: '페이지 스크립트 실행', browser_screenshot: '페이지 스크린샷', terminal_start: '명령 실행', terminal_status: '명령 출력 읽기', python_run: 'Python 실행', http_request: 'HTTP 요청', workbench_file: '파일', workbench_status: '작업대 확인', open_url: '링크 열기', download_file: '다운로드', program_run: '도구 실행', program_save: '도구 저장', wait_for_ui: '화면 대기', ask_user: '질문하기', memory_search: '기억 찾기', memory_save: '기억하기', memory_delete: '기억 삭제', android_intent: '시스템 기능', android_resolve: '앱 기능 확인', android_info: '시스템 상태', android_control: '기기 제어', miniapp_save: '미니 앱 저장', miniapp_open: '미니 앱 열기', miniapp_read: '미니 앱 읽기', miniapp_list: '미니 앱 목록', miniapp_delete: '미니 앱 삭제' },
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
    // ChatGPT web answers in their original Markdown follow chatgpt.com's rules; everything else (API platforms,
    // text reconstructed from the page) keeps this view's own rules.
    answer.innerHTML = item && item.raw && WEB ? window.OPhoneMarkdown.renderWeb(text) : window.OPhoneMarkdown.render(text, item && item.math);
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
      } else if (item.type === 'answer') addAnswer(turn, item.text, isLive, item);
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
    syncModel(snap.model, snap.model_menu);
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
    $('model').textContent = WEB ? state.webModel || t('webModel') : (state.providerLabel || 'DeepSeek') + (state.model ? ' · ' + state.model : '');
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
  // side: 'left' (default, the menu button) or 'right' (pulled from the right edge).
  function openDrawer(side) {
    refreshState();
    renderDrawer();
    document.body.classList.toggle('drawer-right', side === 'right');
    $('scrim').hidden = false;
    requestAnimationFrame(function () { document.body.classList.add('drawer-open'); });
    input.blur();
  }
  function closeDrawer() {
    document.body.classList.remove('drawer-open');
    setTimeout(function () { if (!document.body.classList.contains('drawer-open')) $('scrim').hidden = true; }, 400);
  }
  $('menu').addEventListener('click', function () { openDrawer('left'); });

  /* ---------- the drawer follows a finger pulled in from either screen edge ----------
     Touches that start at an edge (the page receives them where Android leaves the edge to the app), and on
     Android 14+ the system back gesture's progress (native calls edgeDrag), move the drawer with the finger. */
  var drag = null, EDGE = 28;
  function drawerWidth() { return $('drawer').getBoundingClientRect().width + 16 || 340; }
  function dragStart(side) {
    var opening = !document.body.classList.contains('drawer-open');
    if (opening) { refreshState(); renderDrawer(); document.body.classList.toggle('drawer-right', side === 'right'); input.blur(); }
    drag = { side: document.body.classList.contains('drawer-right') ? 'right' : 'left', opening: opening, p: opening ? 0 : 1 };
    $('scrim').hidden = false;
    document.body.classList.add('drawer-dragging');
    dragShow(drag.p);
  }
  function dragShow(p) {
    p = Math.max(0, Math.min(1, p)); drag.p = p;
    var off = (1 - p) * drawerWidth() * (drag.side === 'right' ? 1 : -1);
    $('drawer').style.transform = 'translateX(' + off + 'px)';
    $('scrim').style.opacity = String(p);
  }
  function dragEnd(open) {
    if (!drag) return;
    document.body.classList.remove('drawer-dragging');
    $('drawer').style.transform = ''; $('scrim').style.opacity = '';
    var side = drag.side; drag = null;
    if (open) { $('scrim').hidden = false; document.body.classList.toggle('drawer-right', side === 'right'); document.body.classList.add('drawer-open'); }
    else closeDrawer();
  }
  var touch = null;
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1 || drag || document.querySelector('.sheet-wrap')) return;
    var x = e.touches[0].clientX, w = window.innerWidth, open = document.body.classList.contains('drawer-open');
    var side = x <= EDGE ? 'left' : x >= w - EDGE ? 'right' : '';
    if (!side || open) { touch = null; return; }
    touch = { side: side, x: x, y: e.touches[0].clientY, t: Date.now(), started: false };
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!touch) return;
    var dx = e.touches[0].clientX - touch.x, dy = e.touches[0].clientY - touch.y, inward = touch.side === 'left' ? dx : -dx;
    if (!touch.started) {
      if (Math.abs(dy) > 14 && Math.abs(dy) > inward) { touch = null; return; } // a vertical scroll, not a pull
      if (inward < 8) return;
      touch.started = true; dragStart(touch.side);
    }
    e.preventDefault();
    touch.last = inward; touch.lastT = Date.now();
    dragShow(inward / drawerWidth());
  }, { passive: false });
  function touchDone() {
    if (!touch) return;
    var t = touch; touch = null;
    if (!t.started) return;
    var elapsed = (t.lastT || Date.now()) - t.t, speed = elapsed >= 30 ? (t.last || 0) / elapsed : 0; // px per ms; a fling counts
    dragEnd(drag && (drag.p > 0.35 || speed > 0.5));
  }
  document.addEventListener('touchend', touchDone);
  document.addEventListener('touchcancel', function () { if (touch && touch.started) dragEnd(false); touch = null; });
  $('scrim').addEventListener('click', closeDrawer);
  $('search').addEventListener('input', renderDrawer);
  $('search').placeholder = t('search');
  function startNewChat() { call('newChat'); }
  $('new').addEventListener('click', startNewChat);
  $('drawer-new').addEventListener('click', startNewChat);
  Array.prototype.forEach.call(document.querySelectorAll('[data-open]'), function (b) {
    b.addEventListener('click', function () { closeDrawer(); call('open', b.getAttribute('data-open')); });
  });
  // An open drawer closes with a swipe back toward its own edge (opening is the edge pull above).
  var touchX = null, touchY = null;
  document.addEventListener('touchstart', function (e) { touchX = e.touches[0].clientX; touchY = e.touches[0].clientY; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (touchX === null) return;
    var dx = e.changedTouches[0].clientX - touchX, dy = Math.abs(e.changedTouches[0].clientY - touchY);
    var right = document.body.classList.contains('drawer-right');
    if (document.body.classList.contains('drawer-open') && (right ? dx > 60 : dx < -60) && dy < 50) closeDrawer();
    touchX = null;
  }, { passive: true });

  /* ---------- web: the model and reasoning level follow the ChatGPT page's own pickers ---------- */
  var modelSheet = null, modelRequest = 0, CARET = '<svg class="caret" viewBox="0 0 24 24"><path d="m7 10 5 5 5-5"/></svg>';
  function chipText(chip, text) {
    if (chip._text === text) return;
    chip._text = text;
    chip.innerHTML = '<span></span>' + CARET;
    chip.firstChild.textContent = text;
  }
  function syncModel(m, menu) {
    if (!WEB) return;
    m = m || {};
    state.webModel = m.label || '';
    $('model').textContent = m.label || t('webModel');
    var choosing = modelSheet && modelSheet.phase === 'choosing' ? modelSheet.kind : '';
    $('model-bar').hidden = !m.can_choose;
    chipText($('model-chip'), choosing === 'model' ? t('switching') : m.label || t('webModel'));
    $('effort-chip').hidden = !m.can_choose_effort;
    chipText($('effort-chip'), choosing === 'effort' ? t('switching') : m.effort || '');
    $('model-chip').disabled = $('effort-chip').disabled = state.running;
    if (modelSheet && menu) modelSheetUpdate(menu);
  }
  function closeModelSheet() {
    if (!modelSheet) return;
    clearTimeout(modelSheet.timer);
    if (modelSheet.wrap.parentNode) modelSheet.wrap.parentNode.removeChild(modelSheet.wrap);
    modelSheet = null;
    syncModel(snap && snap.model);
  }
  function openModelSheet(kind) {
    if (state.running) return;
    closeModelSheet();
    var wrap = el('div', 'sheet-wrap'), sheet = el('div', 'sheet model-sheet');
    var title = el('h3'); title.textContent = t(kind === 'effort' ? 'chooseEffort' : 'chooseModel');
    var note = el('p', 'model-note'); note.textContent = t('modelLoading');
    var list = el('div', 'model-list');
    var cancel = el('button', 'cancel press'); cancel.textContent = t('cancel'); cancel.onclick = closeModelSheet;
    [title, note, list, cancel].forEach(function (n) { sheet.appendChild(n); });
    wrap.appendChild(sheet);
    wrap.onclick = function (e) { if (e.target === wrap) closeModelSheet(); };
    document.body.appendChild(wrap);
    var id = 'm' + (++modelRequest) + '-' + Date.now();
    modelSheet = { wrap: wrap, note: note, list: list, kind: kind, id: id, phase: 'loading', at: Date.now() };
    modelSheet.timer = setTimeout(function () { if (modelSheet && modelSheet.id === id && modelSheet.phase !== 'list') modelSheetError('timeout'); }, 20000);
    call('modelMenu', kind, id);
  }
  function modelSheetError(code) {
    var s = modelSheet;
    s.phase = 'error';
    s.note.hidden = false;
    s.note.textContent = t('modelFailed') + (code ? ' · ' + t('errorCode', code) : '');
    s.list.innerHTML = '';
    var open = el('button', 'press'); open.textContent = t('showWeb');
    open.onclick = function () { closeModelSheet(); call('open', 'web'); };
    s.list.appendChild(open);
    syncModel(snap && snap.model);
  }
  function modelSheetUpdate(menu) {
    var s = modelSheet;
    if (s.phase === 'loading' && menu.id === s.id && !menu.busy) {
      if (menu.error || !(menu.items || []).length) { modelSheetError(menu.error || 'empty'); return; }
      s.phase = 'list';
      s.note.hidden = true;
      s.list.innerHTML = '';
      var group = '';
      menu.items.forEach(function (item) {
        if (item.group && item.group !== group) { group = item.group; var g = el('div', 'model-group'); g.textContent = group; s.list.appendChild(g); }
        var b = el('button', 'model-option press' + (item.selected ? ' selected' : ''));
        b.innerHTML = '<span class="text"><span class="name"></span><span class="detail"></span></span>' + (item.selected ? ICONS.check : '');
        b.querySelector('.name').textContent = item.label;
        b.querySelector('.detail').textContent = item.detail || '';
        b.querySelector('.detail').hidden = !item.detail;
        b.onclick = function () {
          if (item.selected) { closeModelSheet(); return; }
          s.phase = 'choosing'; s.chooseAt = Date.now();
          s.note.hidden = false; s.note.textContent = t('switching'); s.list.innerHTML = '';
          clearTimeout(s.timer);
          s.timer = setTimeout(function () { if (modelSheet === s && s.phase === 'choosing') modelSheetError('timeout'); }, 20000);
          call('chooseModel', s.kind, item.label, item.group || '');
          syncModel(snap && snap.model);
        };
        s.list.appendChild(b);
      });
    } else if (s.phase === 'choosing' && String(menu.id).indexOf('choose-') === 0 && !menu.busy && menu.at >= s.chooseAt - 2000) {
      if (menu.error) modelSheetError(menu.error); else closeModelSheet();
    }
  }
  $('model-chip').addEventListener('click', function () { openModelSheet('model'); });
  $('effort-chip').addEventListener('click', function () { openModelSheet('effort'); });

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


  window.OPhoneChatApp = {
    event: function (raw) { var e = typeof raw === 'string' ? json(raw, null) : raw; if (e) onEvent(e); },
    // Back on the chat page never leaves it: it closes a sheet or the drawer, otherwise opens the drawer.
    back: function () {
      var sheet = document.querySelector('.sheet-wrap');
      if (sheet) { var cancel = sheet.querySelector('.cancel'); if (cancel) cancel.click(); else sheet.parentNode.removeChild(sheet); return true; }
      if (document.body.classList.contains('drawer-open')) { closeDrawer(); return true; }
      openDrawer('left'); return true;
    },
    // Android 14+ back gesture: phase start | move (progress 0..1) | end | cancel, from the left or right edge.
    edgeDrag: function (phase, side, progress) {
      if (phase === 'start') { if (!document.querySelector('.sheet-wrap')) dragStart(side === 'right' ? 'right' : 'left'); return; }
      if (!drag) { if (phase === 'end') this.back(); return; }
      if (phase === 'move') dragShow(drag.opening ? progress : 1 - progress);
      else if (phase === 'end') dragEnd(drag.opening);
      else dragEnd(!drag.opening);
    }
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
