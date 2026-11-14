export const name = 'm84m-tools-demo';

// M84-M 验收：插件自己产出**带工具调用**的模型回合 + 逐 token 文本流。
//
// 协议：stream(request, emit)
// - request 是宿主完整的 Anthropic 请求体（system / messages / tools / model …）；
// - emit(chunk) 同步送回一块：'文本' 或 {type:'text',text} / {type:'tool_use',id,name,input} /
//   {type:'usage',input,output} / {type:'stop',reason}；
// - 返回值 {text, toolCalls, stopReason, usage}：text 与已流的文本重复时宿主不会重复渲染，
//   toolCalls 里的每一项会被宿主折成 tool_use 块 → 走宿主原有工具执行、工具卡、tool_result 回传。
export function apply(ctx) {
  ctx.llm.register({
    id: 'm84m.agent',
    label: 'M84-M 工具回合演示',
    model: 'm84m-js-1',
    stream: async function (request, emit) {
      const messages = Array.isArray(request && request.messages) ? request.messages : [];
      const tools = Array.isArray(request && request.tools) ? request.tools : [];
      const lastUser = lastUserText(messages);
      const toolResult = lastToolResult(messages);
      console.log('m84m request messages=' + messages.length + ' tools=' + tools.length +
        ' toolResult=' + (toolResult ? 'yes' : 'no'));

      if (toolResult === null) {
        // 第一轮：先报用量、再逐块吐「正在调用」，最后发起工具调用（真实执行在宿主侧）。
        emit({ type: 'usage', input: 12, output: 8 });
        emit('正在调用 ');
        emit('m84m_ping…');
        return {
          text: '',
          toolCalls: [{ id: 'call_m84m_1', name: 'm84m_ping', input: { text: lastUser.slice(0, 60) } }],
          stopReason: 'tool_use',
          usage: { input: 12, output: 8 }
        };
      }

      // 第二轮：tool_result 回来了，逐 token 吐最终答复（返回值 text 是同一句，宿主只补没流过的尾巴）。
      const text = 'M84M_TOOL_OK ' + toolResult + ' REQ=' + lastUser.slice(0, 60);
      emit({ type: 'usage', input: 40, output: text.length });
      for (let i = 0; i < text.length; i += 6) {
        emit(text.slice(i, i + 6));
      }
      return { text: text, stopReason: 'end_turn', usage: { input: 40, output: text.length } };
    }
  });

  // 工具在插件里注册、由宿主执行；插件模型能调到它，证明「模型由插件产出 + 工具由插件提供」可以组合。
  ctx.tools.register({
    name: 'm84m_ping',
    description: 'M84-M 验收工具：把入参回成 pong-m84m:<text>',
    parameters: {
      type: 'object',
      properties: { text: { type: 'string', description: '回显文本' } }
    },
    execute: async function (args) {
      const text = (args && typeof args.text === 'string') ? args.text : '';
      return { text: 'pong-m84m:' + text };
    }
  });
}

// 最后一条**真正**的 user 文本（跳过宿主注入的 runtime context / system-reminder）。
function lastUserText(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (!message || message.role !== 'user') { continue; }
    const blocks = Array.isArray(message.content) ? message.content : [];
    const texts = [];
    for (let j = 0; j < blocks.length; j++) {
      const block = blocks[j];
      if (!block || block.type !== 'text' || typeof block.text !== 'string') { continue; }
      const text = block.text.trim();
      if (!text) { continue; }
      if (text.indexOf('Current runtime context') === 0) { continue; }
      if (text.indexOf('<system-reminder>') === 0) { continue; }
      texts.push(text);
    }
    if (texts.length > 0) { return texts[texts.length - 1].replace(/\s+/g, ' '); }
  }
  return '(empty)';
}

// 最近一个 tool_result 块的文本（宿主把工具结果作为 user 消息里的 tool_result 回来）。
function lastToolResult(messages) {
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (!message || message.role !== 'user') { continue; }
    const blocks = Array.isArray(message.content) ? message.content : [];
    for (let j = blocks.length - 1; j >= 0; j--) {
      const block = blocks[j];
      if (!block || block.type !== 'tool_result') { continue; }
      if (typeof block.content === 'string') { return block.content; }
      if (Array.isArray(block.content)) {
        const parts = [];
        for (let k = 0; k < block.content.length; k++) {
          const inner = block.content[k];
          if (inner && inner.type === 'text' && typeof inner.text === 'string') { parts.push(inner.text); }
        }
        return parts.join(' ').replace(/\s+/g, ' ');
      }
      return '';
    }
  }
  return null;
}
