// 鲸歌默认壳（M85-B3，Surface 实现，builtin.shell.whale）
//
// 这是「默认鲸歌」的聊天页：全部由 Surface 原语拼，宿主不提供任何聊天组件。
// legacy 原生壳仍保留，可用 `--es dsh_shell native` 切回对拍；M85-C 才删 legacy。
//
// 绑定：消息/思考/工具卡/审批/上下文都走 bind，流式 token 不跨 JS；
// 发送/停止/切会话/审批/goal/提醒走 ctx.surface.command。
//
// M85-B3 新增（仍不含宿主聊天语义）：
// - 文件附件卡「点开看原文」（消息里的 [附件: …] / <attachment/> 块、待发文本文件）；
// - 消息长按 → 引用（宿主在动作 payload 里给一行原文，壳自己塞 `>` 引用块）；
// - 多题各自「其他回答」入口（复用宿主通用 draft，宿主不解释哪一题）；
// - goal 暂停/继续/编辑/清空、会话提醒查看/删除、其它会话等待横幅。

// 壳自己的临时交互状态：不进宿主 state、不落盘（重挂壳即清空）。
let quoteTarget = null;      // { text }：长按引用中的消息原文
let customQid = null;        // 多题自定义：正在输入「其他回答」的问题 id
let customPrevDraft = null;  // 进入自定义前用户正在打的草稿（提交/取消后还原）
let goalOpen = false;        // goal 面板展开
let goalEdit = '';           // '' | 'objective' | 'cap'
let goalClearConfirm = false;
let goalPrevDraft = null;
let goalError = '';
let schedulesOpen = false;

/** markdown 引用块（与 legacy quotePrefix 同一形状：每行 `> ` 前缀）。 */
function quoteBlock(text) {
  const lines = String(text || '').split('\n');
  const out = [];
  for (let i = 0; i < lines.length; i += 1) out.push('> ' + lines[i]);
  return out.join('\n') + '\n\n';
}

function firstLine(text, max) {
  const line = String(text || '').split('\n')[0];
  return line.length > max ? line.substring(0, max) + '…' : line;
}

function findQuestion(state, id) {
  const items = ((state.chat || {}).pendingQuestions || {}).items || [];
  for (let i = 0; i < items.length; i += 1) {
    if (items[i].id === id) return items[i];
  }
  return null;
}

function restoreDraft(text) {
  return text === null || text === undefined ? null : String(text);
}

function toolItem() {
  return {
    type: 'column',
    background: 'surfaceAlt',
    corner: 10,
    padding: 8,
    gap: 3,
    // M85-B2-2：整张卡可点，展开/收起走壳自己的 view 状态（setShellView）
    onTap: 'toggleTool',
    children: [
      {
        type: 'row',
        gap: 6,
        children: [
          { type: 'icon', name: 'build', size: 14, color: 'sub' },
          { type: 'text', bind: 'item.name', size: 13, weight: 'semibold', maxLines: 1, grow: 1 },
          { type: 'text', bind: 'item.status', size: 10, color: 'faint', maxLines: 1 },
          { type: 'text', bind: 'item.duration', size: 10, color: 'faint', maxLines: 1 },
          { type: 'text', bind: 'item.toolLabel', size: 10, color: 'brand', maxLines: 1 }
        ]
      },
      { type: 'text', bind: 'item.arguments', size: 10, color: 'faint', maxLines: 1 },
      { type: 'text', bind: 'item.result', size: 12, color: 'text', maxLines: 2, visible: '!item.toolExpanded' },
      { type: 'text', bind: 'item.result', size: 12, color: 'text', maxLines: 200, visible: 'item.toolExpanded' }
    ]
  };
}

/** 消息里的一个附件：图片缩略图 / 文件卡（可展开原文）。 */
function attachmentItem() {
  return {
    type: 'column',
    gap: 3,
    children: [
      { type: 'image', bind: 'item.path', width: 160, height: 120, corner: 10, fit: 'cover', visible: 'item.isImage' },
      { type: 'text', bind: 'item.name', size: 9, color: 'faint', maxLines: 1, visible: 'item.isImage' },
      {
        type: 'column',
        visible: 'item.isFile',
        background: 'surfaceAlt',
        corner: 8,
        padding: 7,
        gap: 2,
        children: [
          {
            type: 'row',
            gap: 5,
            onTap: 'toggleFile',
            children: [
              { type: 'icon', name: 'folder', size: 14, color: 'brand' },
              { type: 'text', bind: 'item.name', size: 12, weight: 'semibold', grow: 1, maxLines: 1 },
              { type: 'text', bind: 'item.fileLabel', size: 10, color: 'brand', maxLines: 1, visible: 'item.hasBody' }
            ]
          },
          { type: 'text', bind: 'item.detail', size: 9, color: 'faint', maxLines: 1 },
          {
            type: 'text',
            bind: 'item.body',
            size: 11,
            color: 'text',
            mono: true,
            maxLines: 200,
            visible: 'item.fileExpanded'
          }
        ]
      }
    ]
  };
}

function messageItem() {
  return {
    type: 'column',
    paddingV: 7,
    gap: 4,
    // M85-B3：长按消息 → 引用到输入条（宿主只回一行原文，引用格式由壳决定）
    onLongPress: 'quoteMessage',
    children: [
      { type: 'text', bind: 'item.role', size: 10, color: 'faint' },
      {
        type: 'column',
        visible: 'item.hasThinking',
        background: 'surfaceAlt',
        corner: 8,
        padding: 6,
        gap: 3,
        children: [
          {
            type: 'row',
            gap: 4,
            onTap: 'toggleThinking',
            children: [
              { type: 'text', text: '思考', size: 10, color: 'faint', weight: 'medium' },
              { type: 'text', bind: 'item.thinkingChars', size: 9, color: 'faint', grow: 1 },
              { type: 'text', bind: 'item.thinkingLabel', size: 10, color: 'brand' }
            ]
          },
          { type: 'text', bind: 'item.thinking', size: 12, color: 'sub', maxLines: 3, visible: '!item.thinkingExpanded' },
          { type: 'text', bind: 'item.thinking', size: 12, color: 'sub', maxLines: 200, visible: 'item.thinkingExpanded' }
        ]
      },
      {
        type: 'list',
        bind: 'item.attachments',
        visible: 'item.hasAttachments',
        gap: 6,
        item: attachmentItem()
      },
      { type: 'markdown', bind: 'item.text', color: 'text' },
      {
        type: 'list',
        bind: 'item.toolCalls',
        gap: 6,
        item: toolItem()
      },
      {
        type: 'text',
        bind: 'item.error',
        visible: 'item.hasError',
        size: 11,
        color: 'danger'
      }
    ]
  };
}

function approvalCard() {
  return {
    type: 'column',
    visible: 'chat.pendingApproval.active',
    background: 'warnSoft',
    corner: 12,
    padding: 10,
    gap: 6,
    children: [
      {
        type: 'row',
        gap: 6,
        children: [
          { type: 'icon', name: 'warning', size: 16, color: 'warn' },
          { type: 'text', text: '需要确认：', size: 13, weight: 'semibold', color: 'warn' },
          { type: 'text', bind: 'chat.pendingApproval.toolName', size: 13, weight: 'bold', grow: 1 }
        ]
      },
      { type: 'text', bind: 'chat.pendingApproval.summary', size: 12, color: 'text', maxLines: 4 },
      {
        type: 'row',
        gap: 8,
        children: [
          { type: 'button', label: '允许', style: 'primary', onTap: 'approvalAllow' },
          { type: 'button', label: '总是允许', style: 'tonal', onTap: 'approvalAlways' },
          { type: 'button', label: '拒绝', style: 'danger', onTap: 'approvalDeny' }
        ]
      }
    ]
  };
}

function questionItem() {
  return {
    type: 'column',
    width: 'fill',
    gap: 5,
    background: 'surface',
    corner: 10,
    padding: 8,
    children: [
      { type: 'text', bind: 'item.question', size: 13, weight: 'semibold', color: 'text' },
      { type: 'text', text: '可多选', size: 9, color: 'faint', visible: 'item.multiSelect' },
      // 选项放在长 plan 前面：计划审批不用先滚完整篇 plan 才能点「Approve」
      {
        type: 'list',
        bind: 'item.options',
        gap: 4,
        item: {
          type: 'column',
          gap: 2,
          children: [
            { type: 'chip', bind: 'item.label', selected: 'item.selected', onTap: 'answerOption' },
            { type: 'text', bind: 'item.description', size: 9, color: 'faint', visible: 'item.hasDescription', maxLines: 2 }
          ]
        }
      },
      { type: 'markdown', bind: 'item.detail', visible: 'item.hasDetail' },
      // M85-B3：每题都能自定义回答（入口在题内，输入框统一在卡片底部）
      {
        type: 'row',
        gap: 6,
        children: [
          { type: 'button', label: '其他回答', style: 'text', onTap: 'customAnswer' }
        ]
      }
    ]
  };
}

function questionCard(state) {
  const chat = state.chat || {};
  const customQuestion = customQid ? findQuestion(state, customQid) : null;
  const customNodes = [];
  if (customQuestion) {
    customNodes.push({
      type: 'column',
      gap: 4,
      background: 'surface',
      corner: 10,
      padding: 8,
      children: [
        {
          type: 'text',
          text: '其他回答：' + String(customQuestion.question || '').substring(0, 80),
          size: 11,
          color: 'brand',
          maxLines: 2
        },
        {
          type: 'row',
          gap: 6,
          children: [
            { type: 'textfield', bind: 'chat.draft', grow: 1, placeholder: '输入自定义回答（可留空）' },
            { type: 'button', label: '取消', style: 'text', onTap: 'customCancel' }
          ]
        }
      ]
    });
  }
  return {
    type: 'column',
    visible: 'chat.pendingQuestions.active',
    background: 'brandSoft',
    corner: 12,
    padding: 10,
    gap: 8,
    children: [
      {
        type: 'row',
        gap: 6,
        children: [
          { type: 'icon', name: 'help', size: 16, color: 'brand' },
          { type: 'text', bind: 'chat.pendingQuestions.header', size: 13, weight: 'semibold', color: 'brand', grow: 1, maxLines: 2 }
        ]
      },
      {
        // 问题/plan 可能很长：内容区自己滚动，整张卡仍然放得下（外层不滚动，避免 LazyColumn 嵌套）
        type: 'scroll',
        width: 'fill',
        maxHeight: 260,
        children: [
          { type: 'list', bind: 'chat.pendingQuestions.items', lazy: false, width: 'fill', gap: 8, item: questionItem() }
        ]
      }
    ].concat(customNodes).concat([
      {
        type: 'row',
        gap: 8,
        children: [
          { type: 'button', label: '提交', style: 'primary', onTap: 'answerSubmit' },
          { type: 'button', label: '跳过', style: 'tonal', onTap: 'answerSkip' },
          { type: 'button', label: '停止', style: 'text', onTap: 'stop' }
        ]
      }
    ])
  };
}

/** goal 条 + 管理面板（M85-B3：暂停/继续/编辑/清空）。 */
function goalSection() {
  const children = [
    {
      type: 'row',
      onTap: 'goalToggle',
      gap: 6,
      children: [
        { type: 'icon', name: 'flag', size: 13, color: 'brand' },
        { type: 'text', bind: 'chat.goal.objective', size: 11, color: 'text', grow: 1, maxLines: 1 },
        { type: 'text', bind: 'chat.goal.label', size: 10, color: 'brand', maxLines: 1 },
        { type: 'text', text: goalOpen ? '收起' : '管理', size: 10, color: 'brand', maxLines: 1 }
      ]
    }
  ];
  if (goalOpen) {
    children.push({
      type: 'column',
      gap: 6,
      padding: 6,
      background: 'surfaceAlt',
      corner: 8,
      children: [
        {
          type: 'row',
          gap: 6,
          children: [
            { type: 'button', label: '暂停', style: 'tonal', onTap: 'goalPause', visible: 'chat.goal.armed' },
            { type: 'button', label: '继续', style: 'tonal', onTap: 'goalResume', visible: 'chat.goal.resumable' },
            { type: 'button', label: '编辑', style: 'tonal', onTap: 'goalEditStart', visible: 'chat.goal.editable' },
            { type: 'button', label: goalClearConfirm ? '确认清空' : '清空', style: 'text', onTap: 'goalClear' }
          ]
        },
        { type: 'text', text: '目标已完成，不能再暂停/编辑；可继续或清空。', size: 10, color: 'faint', visible: 'chat.goal.complete' },
        { type: 'text', text: '轮次已用尽：先改上限再继续。', size: 10, color: 'warn', visible: 'chat.goal.exhausted' },
        goalError ? { type: 'text', text: goalError, size: 10, color: 'danger', maxLines: 3 } : { type: 'spacer', size: 0 },
        goalEdit ? {
          type: 'column',
          gap: 4,
          children: [
            {
              type: 'text',
              text: goalEdit === 'cap' ? '编辑轮次上限（正整数）' : '编辑目标（非空）',
              size: 10,
              color: 'faint'
            },
            { type: 'textfield', bind: 'chat.draft', placeholder: goalEdit === 'cap' ? '例如 40' : '新目标' },
            {
              type: 'row',
              gap: 6,
              children: [
                { type: 'button', label: '保存', style: 'primary', onTap: 'goalSave' },
                { type: 'button', label: goalEdit === 'cap' ? '改目标' : '改上限', style: 'text', onTap: 'goalEditSwitch' },
                { type: 'button', label: '取消', style: 'text', onTap: 'goalEditCancel' }
              ]
            }
          ]
        } : { type: 'spacer', size: 0 }
      ]
    });
  }
  return {
    type: 'column',
    visible: 'chat.goal.active',
    gap: 4,
    children: children
  };
}

/** 会话提醒：折叠行 + 列表（M85-B3；创建仍由模型的 schedule_* 工具负责）。 */
function schedulesSection() {
  const children = [
    {
      type: 'row',
      visible: 'chat.hasSchedules',
      onTap: 'schedulesToggle',
      gap: 6,
      children: [
        { type: 'icon', name: 'notifications', size: 13, color: 'sub' },
        { type: 'text', text: '会话提醒', size: 11, color: 'sub' },
        { type: 'text', bind: 'chat.schedules.count', size: 10, color: 'faint', grow: 1 },
        { type: 'text', text: schedulesOpen ? '收起' : '查看', size: 10, color: 'brand' }
      ]
    }
  ];
  if (schedulesOpen) {
    children.push({
      type: 'list',
      bind: 'chat.schedules',
      lazy: false,
      gap: 4,
      item: {
        type: 'row',
        gap: 6,
        background: 'surfaceAlt',
        corner: 8,
        paddingH: 8,
        paddingV: 5,
        children: [
          { type: 'text', bind: 'item.title', size: 11, color: 'text', maxLines: 1, grow: 1 },
          { type: 'text', bind: 'item.detail', size: 9, color: 'faint', maxLines: 1 },
          { type: 'button', label: '删除', style: 'text', onTap: 'deleteSchedule' }
        ]
      }
    });
  }
  return { type: 'column', gap: 4, children: children };
}

/** M86-G：持久化队列；已领取的消息不可取消，暂停后由用户明确继续。 */
function queueSection() {
  return {
    type: 'column',
    visible: 'chat.showQueue',
    background: 'surfaceAlt',
    corner: 10,
    padding: 8,
    gap: 4,
    children: [
      {
        type: 'row',
        gap: 6,
        children: [
          { type: 'text', bind: 'chat.queueLabel', size: 11, weight: 'semibold', grow: 1 },
          { type: 'button', label: '继续发送', style: 'tonal', onTap: 'resumeQueue', visible: 'chat.queuePaused', enabled: 'chat.canResumeQueuedPrompts' }
        ]
      },
      { type: 'text', bind: 'chat.queueNotice', size: 10, color: 'sub', visible: 'chat.hasQueueNotice', maxLines: 2 },
      {
        type: 'scroll',
        width: 'fill',
        maxHeight: 140,
        children: [{
          type: 'list',
          bind: 'chat.queuedPrompts',
          lazy: false,
          gap: 4,
          item: {
            type: 'row',
            gap: 6,
            children: [
              {
                type: 'column', grow: 1, gap: 2,
                children: [
                  { type: 'text', bind: 'item.queuePreview', size: 11, maxLines: 1 },
                  { type: 'text', bind: 'item.queueStatus', size: 9, color: 'faint', maxLines: 2 }
                ]
              },
              { type: 'button', label: '取消', style: 'text', onTap: 'cancelQueued', enabled: 'item.canCancel' }
            ]
          }
        }]
      }
    ]
  };
}

/** 引用中提示条：显示被引用消息的首行；发送时由壳拼 `>` 引用块。 */
function quoteRow() {
  return {
    type: 'row',
    background: 'surfaceAlt',
    corner: 8,
    paddingH: 8,
    paddingV: 5,
    gap: 6,
    children: [
      { type: 'icon', name: 'chat', size: 13, color: 'brand' },
      {
        type: 'text',
        text: '引用：' + firstLine(quoteTarget ? quoteTarget.text : '', 60),
        size: 10,
        color: 'sub',
        grow: 1,
        maxLines: 2
      },
      { type: 'button', icon: 'close', style: 'text', onTap: 'quoteCancel' }
    ]
  };
}

function tree(state) {
  const chat = state.chat || {};
  return {
    title: '鲸歌',
    tree: {
      type: 'column',
      padding: 12,
      gap: 8,
      height: 'fill',
      children: [
        // 顶部：会话标题 + 上下文 + 回原生
        {
          type: 'row',
          gap: 8,
          children: [
            { type: 'text', bind: 'chat.title', size: 18, weight: 'bold', grow: 1, maxLines: 1 },
            { type: 'button', label: '原生', icon: 'home', style: 'text', onTap: 'native' }
          ]
        },
        // M85-B3：其它会话在等你（M78 横幅，点一下切过去）
        {
          type: 'row',
          visible: 'chat.hasWaiting',
          background: 'warnSoft',
          corner: 8,
          paddingH: 8,
          paddingV: 5,
          gap: 6,
          onTap: 'focusWaiting',
          children: [
            { type: 'icon', name: 'warning', size: 13, color: 'warn' },
            { type: 'text', bind: 'chat.waiting.label', size: 11, color: 'warn', grow: 1, maxLines: 2 }
          ]
        },
        goalSection(),
        schedulesSection(),
        // M85-B2-2：运行状态头（运行中每秒刷新耗时；结束后显示上一轮用时）
        {
          type: 'text',
          bind: 'chat.turnSummary',
          size: 11,
          color: 'sub',
          visible: 'chat.hasTurnSummary',
          maxLines: 1
        },
        // 会话横条
        {
          type: 'list',
          bind: 'sessions',
          visible: '!chat.pendingQuestions.active',
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
        {
          type: 'row',
          visible: '!chat.pendingQuestions.active',
          gap: 8,
          children: [
            { type: 'button', label: '新会话', icon: 'add', style: 'tonal', onTap: 'newSession' },
            { type: 'text', bind: 'settings.model', size: 11, color: 'faint' },
            { type: 'text', text: '计划模式', size: 10, color: 'brand', visible: 'settings.planActive' }
          ]
        },
        { type: 'divider' },
        // 消息列表：M85-B 的核心，grow 占满剩余高度
        {
          type: 'list',
          bind: 'chat.visibleMessages',
          visible: '!chat.pendingQuestions.active',
          grow: 1,
          gap: 8,
          empty: '发一条消息试试',
          item: messageItem()
        },
        approvalCard(),
        // M85-B2-3：模型提问/计划审批卡（M85-B3：每题可自定义回答）
        questionCard(state),
        queueSection(),
        // M85-B2：待发附件（图片缩略图 / 文件卡），只在有附件时占位
        {
          type: 'column',
          visible: 'chat.hasAttachments',
          gap: 6,
          children: [
            {
              type: 'list',
              bind: 'chat.pendingImages',
              horizontal: true,
              gap: 6,
              item: {
                type: 'row',
                background: 'surfaceAlt',
                corner: 10,
                padding: 4,
                gap: 2,
                children: [
                  { type: 'image', bind: 'item.path', width: 54, height: 54, corner: 8, fit: 'cover' },
                  { type: 'button', icon: 'close', style: 'text', onTap: 'removeImage' }
                ]
              }
            },
            {
              type: 'list',
              bind: 'chat.pendingFiles',
              gap: 6,
              item: {
                type: 'column',
                background: 'surfaceAlt',
                corner: 10,
                padding: 6,
                gap: 3,
                children: [
                  {
                    type: 'row',
                    gap: 4,
                    onTap: 'toggleFile',
                    children: [
                      { type: 'icon', name: 'folder', size: 13, color: 'brand' },
                      { type: 'text', bind: 'item.name', size: 11, maxLines: 1, grow: 1 },
                      { type: 'text', bind: 'item.detail', size: 9, color: 'faint', maxLines: 1 },
                      { type: 'text', bind: 'item.fileLabel', size: 9, color: 'brand', maxLines: 1 },
                      { type: 'button', icon: 'close', style: 'text', onTap: 'removeFile' }
                    ]
                  },
                  { type: 'text', bind: 'item.body', size: 11, color: 'text', mono: true, maxLines: 200, visible: 'item.fileExpanded' }
                ]
              }
            }
          ]
        },
        { type: 'text', bind: 'chat.attachError', visible: 'chat.hasAttachError', size: 11, color: 'danger' },
        {
          type: 'row',
          visible: '!chat.pendingQuestions.active',
          gap: 8,
          children: [
            { type: 'text', text: '上下文', size: 10, color: 'faint' },
            { type: 'text', bind: 'chat.context.label', size: 10, color: 'faint', grow: 1 },
            { type: 'progress', bind: 'chat.context.ratio', width: 80 }
          ]
        },
        // M85-B2-2：本轮用量（宿主已有数据，legacy 早就显示，壳补齐）
        {
          type: 'text',
          bind: 'chat.usage',
          size: 10,
          color: 'faint',
          visible: 'chat.hasUsage',
          maxLines: 2,
          align: 'end'
        },
        // 附件入口（M85-B2）：图片/文件/拍照/截图都是宿主通用命令，壳不碰 Activity
        {
          type: 'row',
          visible: '!chat.pendingQuestions.active',
          gap: 6,
          children: [
            { type: 'button', label: '图片', icon: 'image', style: 'tonal', onTap: 'pickImage' },
            { type: 'button', label: '文件', icon: 'folder', style: 'tonal', onTap: 'pickFile' },
            { type: 'button', label: '拍照', icon: 'camera', style: 'text', onTap: 'pickCamera' },
            { type: 'button', label: '截图', icon: 'screenshot', style: 'text', onTap: 'screenshot' }
          ]
        }
      ]
        // M85-B3：引用中（长按消息 → 引用），发送时把 `>` 引用块拼在正文前。
        // 节点只在 quoting 时出现（visible 只接受 bind 表达式，不能用布尔字面量）。
        .concat(quoteTarget ? [quoteRow()] : [])
        // goal 编辑时把底部输入区让给面板：同一份 chat.draft 不要在两个输入框里各显示一次
        .concat(goalEdit ? [] : [
        {
          type: 'row',
          visible: '!chat.pendingQuestions.active',
          gap: 6,
          children: [
            { type: 'text', text: '生成时发送', size: 10, color: 'faint', grow: 1 },
            { type: 'chip', label: '排队', selected: 'settings.sendWhileRunningQueue', onTap: 'sendModeQueue' },
            { type: 'chip', label: '插话', selected: 'settings.sendWhileRunningSteer', onTap: 'sendModeSteer' }
          ]
        },
        // 输入区（答题时整块让给问题卡，避免长 plan 把按钮挤出屏幕）
        {
          type: 'row',
          visible: '!chat.pendingQuestions.active',
          gap: 8,
          children: [
            {
              type: 'textfield',
              bind: 'chat.draft',
              grow: 1,
              placeholder: '发消息…',
              enabled: '!chat.readOnly',
              onSubmit: 'send'
            },
            { type: 'button', bind: 'chat.sendLabel', icon: 'send', onTap: 'send', enabled: 'chat.canSend' },
            { type: 'button', label: '停止', icon: 'stop', style: 'danger', onTap: 'stop', visible: 'chat.turn.active', enabled: '!chat.stopping' }
          ]
        }
      ])
    }
  };
}

/** 走一次宿主命令并返回 JSON（surface.command 是同步桥；失败给 `{ok:false,error}`）。 */
function command(ctx, name, args) {
  try {
    const reply = ctx.surface.command(name, args || {});
    if (reply && typeof reply.then === 'function') return { ok: false, error: '宿主命令不是同步返回' };
    return reply || { ok: false, error: '宿主没有返回结果' };
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e) };
  }
}

export function apply(ctx) {
  ctx.ui.registerShell({
    id: 'builtin.shell.whale',
    label: '鲸歌默认壳（Surface）',
    mount: function (state) {
      return tree(state);
    },
    onState: function (state) {
      return tree(state);
    },
    onAction: function (action, payload, state) {
      const chat = state.chat || {};
      const value = payload || {};
      const itemId = String(value.itemId || '');
      if (action === 'send') {
        const text = String(chat.draft || '');
        const hasAttachments =
          (chat.pendingImages || []).length > 0 || (chat.pendingFiles || []).length > 0;
        const outgoing = quoteTarget ? quoteBlock(quoteTarget.text) + text : text;
        // M85-B2：只有附件、没有文字也能发（附件本身就是一条消息的内容）
        if (outgoing.trim() || hasAttachments) {
          const reply = command(ctx, 'send', { text: outgoing });
          if (reply.ok === true) quoteTarget = null;
        }
      } else if (action === 'cancelQueued') {
        if (itemId) command(ctx, 'cancelQueuedPrompt', { id: itemId });
      } else if (action === 'resumeQueue') {
        command(ctx, 'resumeQueuedPrompts', {});
      } else if (action === 'sendModeQueue' || action === 'sendModeSteer') {
        command(ctx, 'setSendWhileRunningMode', { mode: action === 'sendModeSteer' ? 'steer' : 'queue' });
      } else if (action === 'pickImage') {
        ctx.surface.command('pickFile', { kind: 'image' });
      } else if (action === 'pickFile') {
        ctx.surface.command('pickFile', { kind: 'file' });
      } else if (action === 'pickCamera') {
        ctx.surface.command('pickFile', { kind: 'camera' });
      } else if (action === 'screenshot') {
        ctx.surface.command('screenshot', {});
      } else if (action === 'removeImage') {
        const index = Number(value.index || 0);
        const image = (chat.pendingImages || [])[index];
        if (image) ctx.surface.command('removeAttachment', { kind: 'image', id: image.id });
      } else if (action === 'removeFile') {
        const index = Number(value.index || 0);
        const file = (chat.pendingFiles || [])[index];
        if (file) ctx.surface.command('removeAttachment', { kind: 'file', name: file.name });
      } else if (action === 'toggleThinking' || action === 'toggleTool' || action === 'toggleFile') {
        // M85-B2-2/B3：展开状态是壳自己的 key-value，宿主只存不解释
        if (itemId) {
          const prefix = action === 'toggleThinking' ? 'thinking:' : (action === 'toggleTool' ? 'tool:' : 'file:');
          ctx.surface.command('setShellView', { key: prefix + itemId, toggle: true });
        }
      } else if (action === 'quoteMessage') {
        const text = String(value.text || '');
        if (text.trim()) quoteTarget = { text: text };
      } else if (action === 'quoteCancel') {
        quoteTarget = null;
      } else if (action === 'answerOption') {
        // itemId = "<问题id>\u001f<选项label>"；单选先清同前缀兄弟项
        const cut = itemId.indexOf('\u001f');
        if (cut > 0) {
          const qid = itemId.substring(0, cut);
          const label = itemId.substring(cut + 1);
          const questions = ((state.chat || {}).pendingQuestions || {}).items || [];
          const question = questions.find(function (q) { return q.id === qid; });
          const args = { key: 'answer:' + qid + ':' + label, toggle: true };
          if (!question || !question.multiSelect) args.clearPrefix = 'answer:' + qid + ':';
          ctx.surface.command('setShellView', args);
        }
      } else if (action === 'customAnswer') {
        // M85-B3：每题都能自定义；同一时间只有一个输入框（复用宿主通用 draft）
        if (itemId) {
          if (customQid !== itemId) {
            customPrevDraft = String(chat.draft || '');
            ctx.surface.command('setDraft', { text: '' });
          }
          customQid = itemId;
        }
      } else if (action === 'customCancel') {
        restoreCustomDraft(ctx);
      } else if (action === 'answerSubmit' || action === 'answerSkip') {
        const chatState = state.chat || {};
        const questions = (chatState.pendingQuestions || {}).items || [];
        const view = chatState.view || {};
        // 自定义回答只归当前选中的那一题（多题时其余题不带 custom）
        const custom = customQid ? String(chatState.draft || '').trim() : '';
        const answers = action === 'answerSkip' ? [] : questions.map(function (q) {
          const prefix = 'answer:' + q.id + ':';
          const selected = Object.keys(view)
            .filter(function (k) { return k.indexOf(prefix) === 0 && view[k]; })
            .map(function (k) { return k.substring(prefix.length); });
          return { id: q.id, selected: selected, custom: q.id === customQid ? custom : '' };
        });
        const reply = command(ctx, 'answer', { answers: answers });
        if (reply && reply.ok === true) {
          customQid = null;
          const before = restoreDraft(customPrevDraft);
          customPrevDraft = null;
          // 宿主 answer 会清空通用 draft；这里恢复答题前用户正在打的草稿
          if (before) ctx.surface.command('setDraft', { text: before });
        }
      } else if (action === 'stop') {
        ctx.surface.command('stop', {});
      } else if (action === 'newSession') {
        ctx.surface.command('newSession', {});
      } else if (action === 'openSession') {
        const index = Number(value.index || 0);
        const session = (state.sessions || [])[index];
        if (session) {
          ctx.surface.command('openSession', { id: session.id });
        }
      } else if (action === 'approvalAllow') {
        ctx.surface.command('approval', { allow: true, always: false });
      } else if (action === 'approvalAlways') {
        ctx.surface.command('approval', { allow: true, always: true });
      } else if (action === 'approvalDeny') {
        ctx.surface.command('approval', { allow: false, always: false });
      } else if (action === 'goalToggle') {
        goalOpen = !goalOpen;
        if (!goalOpen) {
          goalEdit = '';
          goalClearConfirm = false;
        }
      } else if (action === 'goalPause') {
        reportGoal(ctx, command(ctx, 'goal', { action: 'pause' }));
      } else if (action === 'goalResume') {
        reportGoal(ctx, command(ctx, 'goal', { action: 'resume' }));
      } else if (action === 'goalClear') {
        if (!goalClearConfirm) {
          goalClearConfirm = true;
        } else {
          reportGoal(ctx, command(ctx, 'goal', { action: 'clear' }));
          goalClearConfirm = false;
          goalOpen = false;
        }
      } else if (action === 'goalEditStart') {
        goalPrevDraft = String(chat.draft || '');
        goalEdit = 'objective';
        goalError = '';
        ctx.surface.command('setDraft', { text: String((chat.goal || {}).objective || '') });
      } else if (action === 'goalEditSwitch') {
        if (goalEdit === 'cap') {
          goalEdit = 'objective';
          ctx.surface.command('setDraft', { text: String((chat.goal || {}).objective || '') });
        } else {
          goalEdit = 'cap';
          ctx.surface.command('setDraft', { text: String((chat.goal || {}).maxRounds || '') });
        }
      } else if (action === 'goalEditCancel') {
        goalEdit = '';
        goalError = '';
        const back = restoreDraft(goalPrevDraft);
        goalPrevDraft = null;
        if (back !== null) ctx.surface.command('setDraft', { text: back });
      } else if (action === 'goalSave') {
        if (goalEdit === 'cap') {
          const cap = parseInt(String(chat.draft || '').trim(), 10);
          if (!(cap > 0)) {
            goalError = '轮次上限必须是正整数';
          } else {
            reportGoal(ctx, command(ctx, 'goal', { action: 'edit', maxRounds: cap }));
          }
        } else {
          const objective = String(chat.draft || '').trim();
          if (!objective) {
            goalError = '目标不能为空';
          } else {
            reportGoal(ctx, command(ctx, 'goal', { action: 'edit', objective: objective }));
          }
        }
      } else if (action === 'schedulesToggle') {
        schedulesOpen = !schedulesOpen;
      } else if (action === 'deleteSchedule') {
        if (itemId) command(ctx, 'deleteSchedule', { id: itemId });
      } else if (action === 'focusWaiting') {
        command(ctx, 'focusSession', {});
      } else if (action === 'native') {
        ctx.surface.command('closeShell', {});
      }
      return tree(state);
    }
  });
}

/** goal 命令的回执：成功就退出编辑态、还原草稿；失败把宿主文案留在面板里。 */
function reportGoal(ctx, reply) {
  if (reply && reply.ok === true) {
    goalEdit = '';
    goalError = '';
    const back = restoreDraft(goalPrevDraft);
    goalPrevDraft = null;
    if (back !== null) ctx.surface.command('setDraft', { text: back });
  } else {
    goalError = String((reply && reply.error) || '操作失败');
  }
}

/** 取消自定义回答：还原答题前的草稿。 */
function restoreCustomDraft(ctx) {
  customQid = null;
  const back = restoreDraft(customPrevDraft);
  customPrevDraft = null;
  if (back !== null) ctx.surface.command('setDraft', { text: back });
}
