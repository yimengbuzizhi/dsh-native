// M85-C5 演示壳：**同 id 接管**。
//
// 第三方插件用 ctx.ui.registerShell 声明一个和内置 UI 插件**同一个壳 id**的壳
// （id = "builtin.shell.native"）。宿主 `dsh_shell=builtin.shell.native` 时优先选它；
// 停用插件 / `dsh_shell=native` 时回退内置 native 界面。
//
// 界面刻意和原生不同（顶部横幅 + 简化消息流），用来肉眼确认「现在跑的是第三方壳」。

export const name = 'm85c3.sameid';

function messageItem() {
  return {
    type: 'column',
    paddingV: 6,
    children: [
      { type: 'text', bind: 'item.role', size: 11, color: 'faint' },
      { type: 'markdown', bind: 'item.text', color: 'text' }
    ]
  };
}

function tree(state) {
  return {
    title: '鲸歌 · M85-C5 同 id 壳',
    tree: {
      type: 'column',
      padding: 12,
      gap: 8,
      height: 'fill',
      children: [
        {
          type: 'row',
          gap: 8,
          children: [
            { type: 'text', text: 'M85-C5 · 同 id 接管', size: 18, weight: 'bold', grow: 1 },
            { type: 'text', bind: 'chat.status', size: 12, color: 'sub' },
            { type: 'button', label: '原生', icon: 'home', style: 'text', onTap: 'native' }
          ]
        },
        {
          type: 'text',
          text: '壳 id = builtin.shell.native · 提供者 m85c3.sameid（第三方插件）',
          size: 12,
          color: 'sub'
        },
        { type: 'divider' },
        {
          type: 'list',
          bind: 'sessions',
          horizontal: true,
          gap: 6,
          empty: '还没有会话',
          item: {
            type: 'chip',
            bind: 'item.title',
            selected: 'item.current',
            onTap: 'openSession'
          }
        },
        { type: 'button', label: '新会话', icon: 'add', style: 'tonal', onTap: 'newSession' },
        {
          type: 'list',
          bind: 'chat.visibleMessages',
          grow: 1,
          gap: 10,
          empty: '发一条消息试试',
          item: messageItem()
        },
        {
          type: 'row',
          gap: 8,
          children: [
            {
              type: 'textfield',
              bind: 'chat.draft',
              grow: 1,
              placeholder: '输入消息…',
              onSubmit: 'send'
            },
            { type: 'button', label: '发送', icon: 'send', onTap: 'send', visible: '!chat.turn.active' },
            { type: 'button', label: '停止', icon: 'stop', style: 'danger', onTap: 'stop', visible: 'chat.turn.active' }
          ]
        }
      ]
    }
  };
}

export function apply(ctx) {
  ctx.ui.registerShell({
    id: 'builtin.shell.native',
    label: '同 id 第三方壳',
    mount: function (state) {
      return tree(state);
    },
    onState: function (state) {
      return tree(state);
    },
    onAction: function (action, payload, state) {
      payload = payload || {};
      if (action === 'send') {
        const text = String((state.chat && state.chat.draft) || '');
        if (text) ctx.surface.command('send', { text: text });
      } else if (action === 'stop') {
        ctx.surface.command('stop', {});
      } else if (action === 'newSession') {
        ctx.surface.command('newSession', {});
      } else if (action === 'openSession') {
        const index = Number(payload.index);
        const session = (state.sessions || [])[index];
        if (session) ctx.surface.command('openSession', { id: session.id });
      } else if (action === 'native') {
        ctx.surface.command('closeShell', {});
      }
      return tree(state);
    }
  });
}
