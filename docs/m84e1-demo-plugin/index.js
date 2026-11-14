export function apply(ctx) {
  // M84-E1：抑制宿主的运行时上下文快照；插件自己的 context 仍应注入。
  ctx.systemPrompt.suppressRuntimeContext();
  ctx.systemPrompt.context({
    name: 'suppress-demo-context',
    order: 10,
    text: 'SUPPRESS_DEMO_PLUGIN_CONTEXT（插件自己的 context 仍在）'
  });
  ctx.tools.register({
    name: 'suppress_demo_ping',
    description: 'M84-E1 验收工具：回 pong',
    parameters: { type: 'object', properties: {} },
    execute: async () => ({ text: 'pong' })
  });
}
