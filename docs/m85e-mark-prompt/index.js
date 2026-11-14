// M85-E 演示提示词插件（prompt/v1）：接管 builtin.prompt.dsh 这个默认提示词角色。
//
// 宿主每轮把内置 PromptPack 的拼装结果作为 base 传进来（段/变量/角色卡/插件段都已按 M84 排好），
// 这里原样保留并追加一行验收标记；选中后可以 dump system 看到标记，停用后回到内置实现。

export const name = 'm85e.mark-prompt';

export function apply(ctx) {
  ctx.systemPrompt.register({
    id: 'builtin.prompt.dsh',
    label: '标记提示词（演示）',
    assemble(request) {
      const base = String((request && request.base) || '');
      return base + '\n\n【M85E-PROMPT: 提示词由同 id 插件接管】';
    }
  });
}
