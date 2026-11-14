// M85-F WebView 壳演示（shell/v1 第三种后端）。
//
// 验收点：
// - 主界面是一整屏 WebView，HTML 片段由插件返回（不是 Surface 树）；
// - 宿主仍走同一套 shell.mount / shell.state / shell.action / shell.event；
// - 页面里的按钮/输入通过 DshWebShell.action 交给宿主 dispatch shell.action，
//   发消息/停止/新会话/切会话/回原生仍走 ctx.surface.command 白名单。

export const name = 'm85f.webview-shell';

var INTERNAL_KINDS = [
  'goal', 'tool-goal', 'tool-jobs', 'subagent-settled',
  'skill-catalog', 'skill-invocation', 'time-context', 'compact-checkpoint'
];

function esc(value) {
  return String(value === undefined || value === null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function visibleMessages(chat) {
  var out = [];
  var list = chat.messages || [];
  for (var i = 0; i < list.length; i++) {
    var message = list[i] || {};
    if (message.role === 'tool') { continue; }
    if (INTERNAL_KINDS.indexOf(message.sourceKind) >= 0) { continue; }
    if (!message.text) { continue; }
    out.push(message);
  }
  return out;
}

function render(state) {
  state = state || {};
  var chat = state.chat || {};
  var sessions = state.sessions || [];
  var messages = visibleMessages(chat);
  var streaming = chat.streaming === true || (chat.turn && chat.turn.active === true);

  var chips = sessions.map(function (session) {
    var cls = session.current ? 'chip on' : 'chip';
    return '<button class="' + cls + '" onclick="DshWebShell.action(\'openSession\',{id:\'' + esc(session.id) + '\'})">' +
      esc(session.title || session.id) + '</button>';
  }).join('');

  var bubbles = messages.map(function (message) {
    var role = message.role === 'user' ? 'user' : 'assistant';
    return '<div class="msg ' + role + '">' +
      '<div class="who">' + (role === 'user' ? '你' : '鲸歌') + '</div>' +
      '<div class="body">' + esc(message.text).replace(/\n/g, '<br>') + '</div>' +
      '</div>';
  }).join('');

  var composer = streaming
    ? '<button class="btn danger" onclick="DshWebShell.action(\'stop\',{})">停止</button>'
    : '<button class="btn" onclick="DshWebShell.action(\'send\',{text:(document.getElementById(\'dsh-draft\')||{}).value||\'\'})">发送</button>';

  return [
    '<style>',
    '.wv { min-height: 100vh; padding: 14px 12px 18px; background: #f6f7fb; color: #1b1b1f; display: flex; flex-direction: column; gap: 10px; }',
    '.wv .head { display: flex; align-items: center; gap: 8px; }',
    '.wv .title { font-size: 19px; font-weight: 700; flex: 1; }',
    '.wv .status { font-size: 12px; color: #6b7280; }',
    '.wv .chips { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 2px; }',
    '.wv .chip { border: 1px solid #d7dbe7; background: #fff; color: #374151; border-radius: 999px; padding: 6px 12px; font-size: 13px; white-space: nowrap; }',
    '.wv .chip.on { background: #2f6bff; border-color: #2f6bff; color: #fff; }',
    '.wv .msgs { flex: 1; display: flex; flex-direction: column; gap: 10px; padding: 4px 0 10px; }',
    '.wv .msg { max-width: 88%; border-radius: 14px; padding: 8px 11px; background: #fff; box-shadow: 0 1px 2px rgba(16,24,40,.06); }',
    '.wv .msg.user { align-self: flex-end; background: #2f6bff; color: #fff; }',
    '.wv .msg .who { font-size: 11px; opacity: .6; margin-bottom: 2px; }',
    '.wv .msg .body { font-size: 15px; line-height: 1.45; word-break: break-word; }',
    '.wv .empty { color: #9aa3b2; text-align: center; padding: 26px 0; }',
    '.wv .composer { display: flex; gap: 8px; align-items: center; position: sticky; bottom: 0; background: #f6f7fb; padding-top: 8px; }',
    '.wv input { flex: 1; min-width: 0; border: 1px solid #d7dbe7; border-radius: 12px; padding: 11px 12px; font-size: 15px; background: #fff; color: #1b1b1f; }',
    '.wv .btn { border: 0; border-radius: 12px; background: #2f6bff; color: #fff; padding: 11px 16px; font-size: 15px; }',
    '.wv .btn.danger { background: #ef4444; }',
    '.wv .btn.ghost { background: #fff; color: #2f6bff; border: 1px solid #d7dbe7; padding: 7px 12px; font-size: 13px; }',
    '.wv .btn.small { background: #fff; color: #2f6bff; border: 1px solid #d7dbe7; padding: 6px 11px; font-size: 13px; }',
    '.wv .foot { font-size: 11px; color: #9aa3b2; text-align: center; }',
    '</style>',
    '<div class="wv">',
    '  <div class="head">',
    '    <div class="title">鲸歌 · WebView 壳</div>',
    '    <div class="status">' + esc(chat.status || '') + '</div>',
    '    <button class="btn ghost" onclick="DshWebShell.action(\'native\',{})">原生</button>',
    '  </div>',
    '  <div class="chips">',
    '    <button class="btn small" onclick="DshWebShell.action(\'newSession\',{})">+ 新会话</button>',
    chips,
    '  </div>',
    '  <div class="msgs">' + (bubbles || '<div class="empty">发一条消息试试（WebView 壳）</div>') + '</div>',
    '  <div class="composer">',
    '    <input id="dsh-draft" placeholder="输入消息…" value="' + esc(chat.draft || '') + '" onkeydown="if(event.key===\'Enter\'){event.preventDefault();var i=document.getElementById(\'dsh-draft\');if(i&&i.value.trim())DshWebShell.action(\'send\',{text:i.value});}">',
    composer,
    '  </div>',
    '  <div class="foot">WebView 后端 · ' + esc(chat.title || '') + '</div>',
    '</div>'
  ].join('\n');
}

export function apply(ctx) {
  ctx.ui.registerShell({
    id: 'm85f.webview',
    label: 'M85-F WebView 壳',
    backend: 'webview',
    mount: function (state) {
      return { title: '鲸歌 · WebView 壳', html: render(state) };
    },
    onState: function (state) {
      return { html: render(state) };
    },
    onAction: function (action, payload, state) {
      payload = payload || {};
      if (action === 'send') {
        var text = String(payload.text || '');
        if (text.trim()) { ctx.surface.command('send', { text: text }); }
      } else if (action === 'stop') {
        ctx.surface.command('stop', {});
      } else if (action === 'newSession') {
        ctx.surface.command('newSession', {});
      } else if (action === 'openSession') {
        if (payload.id) { ctx.surface.command('openSession', { id: String(payload.id) }); }
      } else if (action === 'native') {
        ctx.surface.command('closeShell', {});
      }
      return { html: render(state) };
    }
  });
}
