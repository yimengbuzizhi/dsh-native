export const name = 'm84j-stream-demo';

// M84-J 验收：插件自己产出模型回合。
// - 宿主完整请求体（Anthropic 形状）进 stream(request)；
// - 中途 await 一个宿主 timer（验证常驻运行时里异步 host 调用能结算）；
// - 返回 { text }，宿主包成最小流，聊天里能看到这句固定前缀。
export function apply(ctx) {
  ctx.llm.register({
    id: 'm84j.echo',
    label: 'M84-J JS stream 演示',
    model: 'm84j-js-1',
    stream: async function (request) {
      const messages = Array.isArray(request && request.messages) ? request.messages : [];
      console.log('m84j request messages=' + messages.length + ' tools=' +
        (Array.isArray(request && request.tools) ? request.tools.length : 0));
      // 宿主把运行时上下文/系统提醒也放在同一条 user 消息的 content 里；演示挑真正的用户输入。
      let lastUser = '';
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
        if (texts.length > 0) {
          lastUser = texts[texts.length - 1].replace(/\s+/g, ' ');
          break;
        }
      }
      // 异步 host 调用：setTimeout 走 timer.wait，完成由宿主线程回灌进同一个 runtime。
      await new Promise(function (resolve) { setTimeout(resolve, 30); });
      const trimmed = lastUser.replace(/\s+/g, ' ').trim().slice(0, 80);
      return { text: 'M84J_STREAM_OK ' + (trimmed || '(empty)') };
    }
  });

  // 同一个插件里工具照旧可用（工具仍是每次调用新建 QuickJS，不影响常驻 stream）。
  ctx.tools.register({
    name: 'm84j_ping',
    description: 'M84-J 验收工具：回 pong-m84j',
    parameters: { type: 'object', properties: {} },
    execute: async function () {
      return { text: 'pong-m84j' };
    }
  });
}
