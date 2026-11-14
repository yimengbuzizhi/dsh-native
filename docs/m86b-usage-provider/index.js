export function apply(ctx) {
  ctx.llm.register({
    id: 'm86.usage.fixture', label: 'M86 确定性用量验证',
    stream: function(request, emit) {
      const text = JSON.stringify(request.messages || []);
      const lower = text.indexOf('M86_LOWER_USAGE') >= 0;
      const zero = text.indexOf('M86_ZERO_USAGE') >= 0;
      emit('M86_USAGE_FIXTURE_OK');
      return {text:'M86_USAGE_FIXTURE_OK',stopReason:'end_turn',usage:{inputTokens:zero ? 0 : (lower ? 8000 : 12000),outputTokens:7,cacheReadInputTokens:zero ? 0 : (lower ? 6000 : 10000)}};
    }
  });
}
