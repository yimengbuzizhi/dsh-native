// M85-E 演示模型插件（model/v1）：声明 provider id = deepseek，与内置 builtin.model.deepseek 同 id。
//
// 启用时按「同 id 第三方优先」接管 deepseek 通道，不走网络、直接返回固定文本；
// 停用后宿主重新注册内置 DeepSeek 通道（选中 deepseek 时恢复真实模型）。
// 如果内置模型插件也被停用，则选 deepseek 会如实报错，不会悄悄换通道。

export const name = 'm85e.model-override';

export function apply(ctx) {
  ctx.llm.register({
    id: 'deepseek',
    label: 'DeepSeek 覆盖（演示）',
    stream(request) {
      return {
        text: 'MODEL-OVER: 这一轮由同 id 模型插件接管（M85-E）',
        stopReason: 'end_turn'
      };
    }
  });
}
