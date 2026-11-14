export function apply(ctx) {
  // M84-E2：配置式模型供应商。
  // 不写 baseUrl/apiKey = 跟随宿主当前端点与 key（演示用，不把 key 写进插件包）。
  // 写 model 覆盖请求体的模型名——真机用抓包/日志证明它真的到达上游。
  ctx.llm.register({
    id: 'e2.demo.provider',
    label: 'E2 演示供应商（跟随宿主端点）',
    // 不写 protocol = 跟随宿主当前协议（这台设备是 OpenAI 兼容，写死 anthropic 会打错端点）
    model: 'deepseek-chat'
  });

  // 负向探针：故意给一个不存在的模型名。用它跑一轮应该由上游报「模型不存在」，
  // 证明插件登记的不是装饰性数据，模型名真的进了请求体。
  ctx.llm.register({
    id: 'e2.demo.probe',
    label: 'E2 负向探针（无效模型）',
    model: 'm84-e2-model-does-not-exist'
  });

  // M84-E2：页面路由（最小版：元数据 + 打开插件自己的 dsh.client）。
  ctx.ui.registerRoute({
    path: '/m84e2',
    title: 'E2 演示页',
    description: '打开 m84e2-demo 自己的 client.js（不是宿主原生页）',
    page: true
  });

  // M84-G：整页插件页面——id 与宿主内置页同名时替换它（这里把「关于」页换成插件页）。
  ctx.ui.registerPage({
    path: 'About',
    title: 'E2 替换的关于页',
    description: '插件整页替换宿主「关于」页；停用插件后恢复宿主页'
  });

  // 这两项仍是明确 unsupported，插件页会显示在「用到但鲸歌未支持的能力」。
  ctx.ui.registerView({ mount() {} });
  ctx.sessionProjections.register({ key: 'e2' });

  ctx.tools.register({
    name: 'm84e2_ping',
    description: 'M84-E2 验收工具：回 pong-e2',
    parameters: { type: 'object', properties: {} },
    execute: async () => ({ text: 'pong-e2' })
  });
}
