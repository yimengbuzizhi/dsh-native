import { z } from '@deepseek-ai/schemastery';

export const name = 'm84h-projection-demo';
export const inject = ['sessionProjections'];

// 验收点：这是一个**闭包在常驻运行时里**的投影。宿主每提交一条会话事件就回调一次 apply；
// 如果运行时是「一次调用一个」，计数永远从 0 开始，真机 dump 会是 turns=0/userMessages=0。
export function apply(ctx) {
  ctx.sessionProjections.register({
    key: 'm84hTurns',
    stateVersion: 1,
    stateSchema: z.object({
      turns: z.number().default(0),
      userMessages: z.number().default(0),
      assistantMessages: z.number().default(0),
      toolResults: z.number().default(0),
      events: z.number().default(0),
      lastType: z.string().default('')
    }),
    init: function () {
      return {
        turns: 0,
        userMessages: 0,
        assistantMessages: 0,
        toolResults: 0,
        events: 0,
        lastType: ''
      };
    },
    apply: function (state, event) {
      const type = String(event && event.type ? event.type : '');
      const tracked = type === 'turn/start' || type === 'user/message' ||
        type === 'assistant/message' || type === 'tool/result';
      // DSH 纪律：不关心的事件返回同一个 state 引用，宿主侧零下游工作。
      if (!tracked) {
        return state;
      }
      return {
        turns: state.turns + (type === 'turn/start' ? 1 : 0),
        userMessages: state.userMessages + (type === 'user/message' ? 1 : 0),
        assistantMessages: state.assistantMessages + (type === 'assistant/message' ? 1 : 0),
        toolResults: state.toolResults + (type === 'tool/result' ? 1 : 0),
        events: state.events + 1,
        lastType: type
      };
    },
    wire: {
      view: function (state) {
        return {
          turns: state.turns,
          userMessages: state.userMessages,
          assistantMessages: state.assistantMessages,
          toolResults: state.toolResults,
          events: state.events,
          lastType: state.lastType
        };
      }
    }
  });

  // 顺带证明同一个插件里工具照旧可用（工具仍是每次调用新建 QuickJS，不影响投影）。
  ctx.tools.register({
    name: 'm84h_ping',
    description: 'M84-H 验收工具：回 pong-m84h',
    parameters: { type: 'object', properties: {} },
    execute: async function () {
      return { text: 'pong-m84h' };
    }
  });
}
