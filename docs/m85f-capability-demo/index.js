// M85-F 能力演示插件（capability/v1）。
//
// 验收点：
// - NFC / adb-shell 这类特权能力由插件提供（宿主不写死），调用返回插件结果；
// - 同 id 覆盖内置 notify：启用时 capability.list 里 notify 的提供方变成这个插件；
// - 停用插件后：nfc / adb-shell 解析为「不可用」，notify 回落到内置 Toast。

export const name = 'm85f-capability-demo';

export function apply(ctx) {
  // 同 id 覆盖内置 notify：证明「能力提供方不是宿主写死的」。
  // 这里不真的弹通知，只回一个标记，方便在 dump 里确认执行的是插件实现。
  ctx.capability.register({
    id: 'notify',
    label: 'M85-F 覆盖通知',
    description: '同 id 覆盖内置 notify，返回 M85F-CAP-NOTIFY 标记',
    kind: 'demo',
    invoke: function (args) {
      args = args || {};
      return {
        ok: true,
        value: {
          via: 'm85f.capability-demo',
          title: String(args.title || ''),
          body: String(args.body || ''),
          marker: 'M85F-CAP-NOTIFY'
        }
      };
    }
  });

  // 特权能力示例 1：NFC（模拟，不碰硬件）。
  ctx.capability.register({
    id: 'nfc',
    label: 'NFC 读卡（演示）',
    description: '模拟 NFC 读卡：返回固定标签 id，不读真实硬件',
    kind: 'device',
    invoke: function (args) {
      args = args || {};
      return {
        ok: true,
        value: {
          tag: 'M85F-NFC-TAG-001',
          action: String(args.action || 'read'),
          simulated: true
        }
      };
    }
  });

  // 特权能力示例 2：adb-shell（模拟，不执行命令）。
  ctx.capability.register({
    id: 'adb-shell',
    label: 'ADB shell（演示）',
    description: '模拟 adb-shell：原样回显命令，不执行',
    kind: 'privileged',
    invoke: function (args) {
      args = args || {};
      return {
        ok: true,
        value: {
          cmd: String(args.cmd || ''),
          output: 'uid=2000(shell) (simulated)',
          simulated: true
        }
      };
    }
  });
}
