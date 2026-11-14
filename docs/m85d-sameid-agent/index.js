// M85-D 演示：**同 id 回合接管**。
//
// 插件声明 agent id = "builtin.agent.dsh"（与内置回合同 id）。宿主 `dsh_agent` 为空 /
// builtin.agent.dsh 时优先选它；插件停用后自动回到内置 TurnEngine。
// `dsh_agent=native` 是唯一的「强制内置」写法（与 dsh_shell=native 同语义）。

export const name = 'm85d.sameid-agent';

export function apply(ctx) {
  ctx.agent.register({
    id: 'builtin.agent.dsh',
    label: '同 id 回合（第三方接管）',
    run: (turn, hostCtx) => {
      hostCtx.status('同 id 回合在处理…');
      return {
        stopReason: 'completed',
        text: 'SAMEID-ROUND: ' + String(turn.input || '')
      };
    }
  });
}
