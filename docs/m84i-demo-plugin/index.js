export const name = 'm84i-events-demo';

// M84-I 验收：
// 1) ctx.on('turn/start' / 'tool/result') 在常驻运行时里真的被宿主事件调用；
// 2) ctx.effect 返回的 cleanup 在插件停用/重载/删除时真的被执行；
// 3) 同一个插件里的会话投影不受影响（事件同时喂 fold 与订阅者）。
export function apply(ctx) {
  let turnCount = 0;

  ctx.on('turn/start', function (event) {
    turnCount += 1;
    console.log('m84i turn/start #' + turnCount + ' seq=' + event.seq);
  });

  ctx.on('tool/result', function (event) {
    console.log('m84i tool/result seq=' + event.seq);
  });

  ctx.effect(function () {
    return function cleanup() {
      console.log('m84i cleanup 执行，turn/start 共 ' + turnCount + ' 次');
    };
  });

  ctx.sessionProjections.register({
    key: 'm84iTurns',
    stateVersion: 1,
    init: function () {
      return { turns: 0 };
    },
    apply: function (state, event) {
      if (event.type !== 'turn/start') {
        return state;
      }
      return { turns: state.turns + 1 };
    },
    wire: {
      view: function (state) {
        return { turns: state.turns };
      }
    }
  });
}
