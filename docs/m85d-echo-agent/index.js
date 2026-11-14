// M85-D 演示回合插件（agent/v1）：输入什么就原样回什么。
//
// 十几行的意义：证明「回合循环可以被插件替换」。宿主在 `dsh_agent` 选中本插件后，
// 每条消息 dispatch `agent.run({turnId, sessionId, input, state}, ctx)` 到这里；
// 返回值 { stopReason, text } 由宿主按正常会话事件落库（切回内置引擎不丢数据）。
// ctx 原语：notify / status / abort / tools.names（tools.invoke / llm / storage 在 M85-E 开放）。

export const name = 'm85d.echo-agent';

export function apply(ctx) {
  ctx.agent.register({
    id: 'm85.echo',
    label: 'Echo 回合（演示）',
    run: async (turn, hostCtx) => {
      hostCtx.status('Echo 回合处理中…');
      const input = String(turn.input || '');
      return {
        stopReason: 'completed',
        text: 'ECHO: ' + input
      };
    }
  });
}
