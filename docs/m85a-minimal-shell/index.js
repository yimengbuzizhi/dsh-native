// M85-A 演示壳：一个第三方插件，用 ctx.ui.registerShell 提供整个主界面。
//
// 验收点：
// - 主界面全部由 Surface 原语拼出来（column/row/list/text/markdown/textfield/button/chip/spacer/divider）；
// - 消息/流式文本走 bind（宿主直接读 state，不跨 JS）；
// - 发送/停止/切会话/新会话走 ctx.surface.command；打开设置/回原生壳也走命令。

export const name = 'm85a-minimal-shell';

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
  const chat = state.chat || {};
  const sessions = state.sessions || [];
  return {
    title: '鲸歌 · M85-A 插件壳',
    tree: {
      type: 'column',
      padding: 12,
      gap: 8,
      height: 'fill',
      children: [
        // 顶部：标题 + 状态 + 回原生
        {
          type: 'row',
          gap: 8,
          children: [
            { type: 'text', text: '鲸歌 · 插件壳', size: 18, weight: 'bold', grow: 1 },
            { type: 'text', bind: 'chat.status', size: 12, color: 'sub' },
            { type: 'button', label: '原生', icon: 'home', style: 'text', onTap: 'native' }
          ]
        },
        // 会话横条：list + item 模板；当前会话高亮
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
        { type: 'divider' },
        // 消息列表：grow 占满剩余高度；item.text 由宿主绑定直接更新（流式 token 不跨 JS）
        {
          type: 'list',
          bind: 'chat.visibleMessages',
          grow: 1,
          gap: 10,
          empty: '发一条消息试试',
          item: messageItem()
        },
        // 输入区
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
    id: 'm85a.chat',
    label: 'M85-A 最小壳',
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
        if (text) {
          ctx.surface.command('send', { text: text });
        }
      } else if (action === 'stop') {
        ctx.surface.command('stop', {});
      } else if (action === 'newSession') {
        ctx.surface.command('newSession', {});
      } else if (action === 'openSession') {
        const index = Number(payload.index);
        const session = (state.sessions || [])[index];
        if (session) {
          ctx.surface.command('openSession', { id: session.id });
        }
      } else if (action === 'native') {
        ctx.surface.command('closeShell', {});
      }
      return tree(state);
    }
  });
}
