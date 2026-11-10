/*
 * 宿主适配层（M72 / Phase 2）：dsh-plugin-whale（Makesupper，MIT）。
 *
 * 插件自己的浏览器半侧（`lib/client.js`，143KB，原样从安装目录载入）做两件事：
 *   1. 每 750ms 轮询 `/dsh-whale/state`，把 LLM 回复句子逐句交给 Web Speech 播报；
 *   2. 播报期间按节拍 POST `/dsh-whale/knock` 敲木鱼，把 token 换算成功德/体重。
 * 这些接口在桌面端由插件的 Node 半侧（`src/services/client-api.ts` + `whale-service.ts`）
 * 挂在 `ctx.webServer` 上；Android 没有 Node 宿主，所以这里在页面里补一个**同语义**的实现：
 *
 *   - `__DSH_HOST_FETCH__` 接 `/dsh-whale/*`：state / knock / speech-ack / auto /
 *     tokens-per-merit，字段与阈值照上游（108 次/批、±1~±1000 万 token 阈值、
 *     30 分钟常驻伴奏上限、pendingTokens 攒满一步折 1kg）；
 *   - 功德账本落 native KV（`SharedPreferences`，键前缀由 native 加插件 id），杀进程不丢；
 *   - 语音来源换成会话快照：`__DSH_HOST__.onSession` 收到 `reply/replySeq` 就按上游
 *     `extractFinalConclusionText` + `splitTextIntoSpeechSentences` 取最后一段切句入队
 *     （这两个纯函数照搬上游 `llm-speech-service.ts`）；`tokens/tokensSeq` 走上游
 *     `consumeTokensAndKnock` 的换算（pendingTokens 攒满 tokensPerMerit 折 1kg 功德）。
 *
 * 诚实限制（不要在文档里吹）：
 *   - Android WebView 没有 Web Speech 语音包：默认预置 `voiceEnabled=false`，走上游自带的
 *     「静音也显示 + 敲一下」降级路径（每句 900ms、有口型动作），不朗读；
 *   - 插件设置面板里的 URL 输入框弹不出键盘（悬浮窗 FLAG_NOT_FOCUSABLE），只能看不能改。
 *
 * 这个文件是**宿主兼容层**，不改插件逻辑；插件换版本时只可能因为接口/类名变化而需要更新。
 */
(function () {
  'use strict';

  var HOST = window.__DSH_HOST__;
  if (!HOST) return;

  var GREETING = '你好呀，我是深度求索鲸鱼娘！随时准备为你播报 LLM 回复并敲木鱼伴奏~';
  var MAX_SENTENCE_LENGTH = 140;
  var MAX_QUEUE_SIZE = 64;
  var MAX_KNOCK_BATCH = 108;
  var AUTO_MAX_DURATION_MS = 30 * 60 * 1000;
  var API_BASE = '/dsh-whale';

  // 窗口尺寸（CSS px ≈ dp）：默认 / 设置面板打开 / 收起。
  //
  // 为什么这么小：Android 12+ 会把「手势穿透（FLAG_NOT_TOUCHABLE）」的悬浮窗当作不可信遮挡，
  // 强制窗口 alpha=0.8（界面发灰、下层内容透出来）。所以有适配层的插件走「窗口可触摸 +
  // 窗口收到 UI 包围盒」这一条：320dp 窗口里挂件实际占 272×251 + 右下 28/36 留白，
  // 窗口收到 306×302 就几乎没有空白区域，穿透由几何保证，颜色也不再被压暗。
  var SIZE_DEFAULT = [306, 302];
  var SIZE_PANEL = [356, 620];
  var SIZE_COLLAPSED = [230, 180];

  // ---- 1. 上游纯函数（照搬 src/services/llm-speech-service.ts，MIT） -----------

  function sanitizeLlmTextForSpeech(raw) {
    if (!raw || typeof raw !== 'string') return '';
    var text = raw;
    text = text.replace(/<think(?:ing)?>[\s\S]*?<\/think(?:ing)?>/gi, ' ');
    text = text.replace(/<think(?:ing)?>[\s\S]*$/gi, ' ');
    text = text.replace(/```[\s\S]*?```/g, ' ');
    text = text.replace(/`([^`]+)`/g, '$1');
    text = text.replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1');
    text = text.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
    text = text.replace(/https?:\/\/[^\s)]+/g, ' ');
    text = text.replace(/<[^>]+>/g, ' ');
    text = text.replace(/^\s*#{1,6}\s+/gm, '');
    text = text.replace(/^\s*>\s+/gm, '');
    text = text.replace(/^\s*[-*+]\s+/gm, '');
    text = text.replace(/([a-zA-Z0-9])_([a-zA-Z0-9])/g, '$1 $2');
    text = text.replace(/::|->|=>|===|!==/g, ' ');
    text = text.replace(/([a-zA-Z0-9])\/([a-zA-Z0-9])/g, '$1 $2');
    text = text.replace(/[*~]+/g, '');
    text = text.replace(/([\u4e00-\u9fa5])([a-zA-Z0-9])/g, '$1 $2');
    text = text.replace(/([a-zA-Z0-9])([\u4e00-\u9fa5])/g, '$1 $2');
    text = text.replace(/[ \t\r]+/g, ' ');
    return text.trim();
  }

  function splitTextIntoSpeechSentences(raw) {
    var cleaned = sanitizeLlmTextForSpeech(raw);
    if (!cleaned) return [];
    var parts = cleaned
      .split(/(?<=[。！？；!?;\n]|\.(?=\s+[A-Z\u4e00-\u9fa5]|$))\s*/)
      .map(function (s) { return s.replace(/\s+/g, ' ').trim(); })
      .filter(Boolean);
    var result = [];
    var pushChunked = function (chunk) {
      var rest = chunk.trim();
      while (rest.length > MAX_SENTENCE_LENGTH) {
        var cutIdx = rest.lastIndexOf(' ', MAX_SENTENCE_LENGTH);
        if (cutIdx < MAX_SENTENCE_LENGTH * 0.6) cutIdx = MAX_SENTENCE_LENGTH;
        result.push(rest.slice(0, cutIdx).trim());
        rest = rest.slice(cutIdx).trim();
      }
      if (rest.length > 0) result.push(rest);
    };
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (part.length <= MAX_SENTENCE_LENGTH) { result.push(part); continue; }
      var subParts = part.split(/(?<=[，,、：:])\s*/).map(function (s) { return s.trim(); }).filter(Boolean);
      var buffer = '';
      for (var j = 0; j < subParts.length; j++) {
        var sub = subParts[j];
        if (sub.length > MAX_SENTENCE_LENGTH) {
          if (buffer) { pushChunked(buffer); buffer = ''; }
          pushChunked(sub);
          continue;
        }
        if (!buffer) buffer = sub;
        else if (buffer.length + sub.length <= MAX_SENTENCE_LENGTH) buffer += sub;
        else { result.push(buffer); buffer = sub; }
      }
      if (buffer) pushChunked(buffer);
    }
    return result.filter(function (s) { return s.length > 0; });
  }

  function extractFinalConclusionText(rawText) {
    var cleaned = sanitizeLlmTextForSpeech(rawText);
    if (!cleaned) return '';
    var paragraphs = cleaned.split(/\n{2,}|\n/)
      .map(function (p) { return p.trim(); })
      .filter(function (p) { return p.length > 0; });
    if (paragraphs.length === 0) return '';
    return paragraphs[paragraphs.length - 1];
  }

  // ---- 2. 小工具 -------------------------------------------------------------

  function clampInt(value, fallback, min, max) {
    var num = Number(value);
    if (!isFinite(num)) return fallback;
    return Math.min(max, Math.max(min, Math.floor(num)));
  }

  function clampSignedStep(value, fallback) {
    var num = Number(value);
    if (!isFinite(num) || num === 0) return fallback;
    var sign = num < 0 ? -1 : 1;
    return sign * Math.min(10000000, Math.max(1, Math.floor(Math.abs(num))));
  }

  function todayKey() {
    var now = new Date();
    var m = String(now.getMonth() + 1);
    var d = String(now.getDate());
    return now.getFullYear() + '-' + (m.length < 2 ? '0' + m : m) + '-' + (d.length < 2 ? '0' + d : d);
  }

  var WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];

  function buildRecords(days) {
    var keys = Object.keys(days).sort().reverse();
    var out = [];
    for (var i = 0; i < keys.length; i++) {
      var key = keys[i];
      var parts = key.split('-');
      var date = new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
      out.push({
        dateKey: key,
        displayDate: Number(parts[1]) + '月' + Number(parts[2]) + '日 ' + (WEEKDAYS[date.getDay()] || ''),
        count: days[key].merit || 0,
        tokens: days[key].tokens || 0
      });
    }
    return out;
  }

  // ---- 3. 功德账本：native KV 落盘（结构同上游 merit_history.json） --------------

  var LEDGER_KEY = 'whale_ledger';

  function readLedger() {
    var parsed = null;
    try { parsed = JSON.parse(HOST.kvGet(LEDGER_KEY) || 'null'); } catch (e) { parsed = null; }
    if (!parsed || typeof parsed !== 'object' || !parsed.days || typeof parsed.days !== 'object') {
      return { days: {}, pendingTokens: 0 };
    }
    if (typeof parsed.pendingTokens !== 'number' || !isFinite(parsed.pendingTokens)) parsed.pendingTokens = 0;
    return parsed;
  }

  function writeLedger(ledger) {
    HOST.kvSet(LEDGER_KEY, JSON.stringify(ledger));
  }

  function ensureBucket(ledger) {
    var key = todayKey();
    if (!ledger.days[key] || typeof ledger.days[key] !== 'object') ledger.days[key] = { merit: 0, tokens: 0 };
    if (typeof ledger.days[key].merit !== 'number') ledger.days[key].merit = 0;
    if (typeof ledger.days[key].tokens !== 'number') ledger.days[key].tokens = 0;
    return ledger.days[key];
  }

  function statsOf(ledger) {
    var key = todayKey();
    var bucket = ledger.days[key] || { merit: 0, tokens: 0 };
    var totalMerit = 0;
    var totalTokens = 0;
    Object.keys(ledger.days).forEach(function (k) {
      totalMerit += ledger.days[k].merit || 0;
      totalTokens += ledger.days[k].tokens || 0;
    });
    return {
      todayMerit: bucket.merit || 0,
      totalMerit: totalMerit,
      todayTokens: bucket.tokens || 0,
      totalTokens: totalTokens,
      pendingTokens: ledger.pendingTokens || 0,
      todayKey: key,
      records: buildRecords(ledger.days)
    };
  }

  function readStats() {
    return statsOf(readLedger());
  }

  /** 敲一下木鱼：+count 功德（一天一桶，超上限的批次由调用方 clamp）。 */
  function knockStats(count) {
    var ledger = readLedger();
    var bucket = ensureBucket(ledger);
    bucket.merit += count;
    writeLedger(ledger);
    knockSeq += 1;
    return statsOf(ledger);
  }

  /** token → 功德：照上游 `consumeTokensAndKnock(tokens, step, 108, capTokens=false)`。 */
  function consumeTokens(tokens) {
    var safeTokens = Number(tokens);
    if (!isFinite(safeTokens) || safeTokens <= 0) return readStats();
    safeTokens = Math.round(safeTokens);
    var step = Math.abs(getTokensPerMerit());
    if (step <= 0) step = 1;
    var direction = getTokensPerMerit() < 0 ? -1 : 1;
    var ledger = readLedger();
    var bucket = ensureBucket(ledger);
    var previousPending = Number(ledger.pendingTokens) || 0;
    var totalPool = previousPending + safeTokens;
    var fullSteps = Math.floor(totalPool / step);
    bucket.merit += direction * fullSteps;
    bucket.tokens += safeTokens;
    ledger.pendingTokens = totalPool % step;
    writeLedger(ledger);
    if (fullSteps > 0) knockSeq += Math.min(MAX_KNOCK_BATCH, fullSteps);
    return statsOf(ledger);
  }

  // ---- 4. 宿主偏好（token 阈值 / 伴奏节拍 / 模型地址） ---------------------------

  var PREFS_KEY = 'whale_prefs';
  var hostPrefs = { tokensPerMerit: 10000, accompanimentIntervalMs: 1200, modelUrl: '' };

  (function loadHostPrefs() {
    var saved = null;
    try { saved = JSON.parse(HOST.kvGet(PREFS_KEY) || 'null'); } catch (e) { saved = null; }
    if (!saved || typeof saved !== 'object') return;
    if (typeof saved.tokensPerMerit === 'number') hostPrefs.tokensPerMerit = clampSignedStep(saved.tokensPerMerit, 10000);
    if (typeof saved.accompanimentIntervalMs === 'number') {
      hostPrefs.accompanimentIntervalMs = clampInt(saved.accompanimentIntervalMs, 1200, 200, 60000);
    }
    if (typeof saved.modelUrl === 'string') hostPrefs.modelUrl = saved.modelUrl.slice(0, 512);
  })();

  function saveHostPrefs() {
    HOST.kvSet(PREFS_KEY, JSON.stringify(hostPrefs));
  }

  function getTokensPerMerit() {
    var value = Number(hostPrefs.tokensPerMerit);
    if (!isFinite(value) || value === 0) return 10000;
    return Math.min(10000000, Math.max(1, Math.floor(Math.abs(value)))) * (value < 0 ? -1 : 1);
  }

  // ---- 5. 语音队列 / 常驻伴奏（同上游 llm-speech-service + auto knock） ----------

  var speech = {
    seq: 0,
    acked: 0,
    pending: [],
    current: { seq: 0, text: GREETING, createdAt: Date.now(), source: 'greeting' }
  };
  var lastLlmText = '';
  var knockSeq = 0;
  var auto = { running: false, targetCount: 0, currentCount: 0, intervalMs: 1200, cycleTitle: '深海常驻伴奏' };
  var autoTimer = null;

  function enqueueSpeech(text, source) {
    var body = source === 'llm' ? extractFinalConclusionText(text) : String(text == null ? '' : text);
    var sentences = splitTextIntoSpeechSentences(body);
    if (sentences.length === 0) return [];
    var joined = sentences.join(' ');
    if (source === 'llm' && joined === lastLlmText) return [];
    if (source === 'llm') lastLlmText = joined;
    // 上游：新的最终回复会顶掉还没播的旧句子（只读最新结果）。
    if (speech.seq > speech.acked) speech.acked = speech.seq;
    speech.pending = [];
    var now = Date.now();
    var added = [];
    for (var i = 0; i < sentences.length; i++) {
      speech.seq += 1;
      var segment = { seq: speech.seq, text: sentences[i], createdAt: now, source: source };
      speech.pending.push(segment);
      added.push(segment);
      speech.current = segment;
    }
    if (speech.pending.length > MAX_QUEUE_SIZE) {
      speech.pending.splice(0, speech.pending.length - MAX_QUEUE_SIZE);
    }
    return added;
  }

  function ackSpeech(seq) {
    var num = Number(seq);
    if (isFinite(num) && num > speech.acked) speech.acked = Math.min(speech.seq, Math.floor(num));
    speech.pending = speech.pending.filter(function (seg) { return seg.seq > speech.acked; });
    return speech.acked;
  }

  function stopAuto() {
    if (autoTimer !== null) {
      clearInterval(autoTimer);
      autoTimer = null;
    }
    auto.running = false;
  }

  function startAuto() {
    stopAuto();
    auto.running = true;
    auto.currentCount = 0;
    auto.targetCount = 0;
    auto.intervalMs = clampInt(hostPrefs.accompanimentIntervalMs, 1200, 200, 60000);
    auto.cycleTitle = '深海常驻伴奏';
    var startedAt = Date.now();
    autoTimer = setInterval(function () {
      if (Date.now() - startedAt >= AUTO_MAX_DURATION_MS) {
        stopAuto();
        return;
      }
      knockStats(1);
      auto.currentCount += 1;
    }, auto.intervalMs);
  }

  // ---- 6. `/dsh-whale` 服务端（照插件自己的 src/services/client-api.ts） --------

  function buildState() {
    var stats = readStats();
    return {
      apiVersion: 2,
      todayMerit: stats.todayMerit,
      totalMerit: stats.totalMerit,
      todayTokens: stats.todayTokens,
      totalTokens: stats.totalTokens,
      pendingTokens: stats.pendingTokens,
      tokensPerMerit: getTokensPerMerit(),
      accompanimentIntervalMs: hostPrefs.accompanimentIntervalMs,
      modelUrl: hostPrefs.modelUrl,
      todayKey: stats.todayKey,
      currentSpeech: speech.current,
      speechSeq: speech.seq,
      ackedSpeechSeq: speech.acked,
      speechQueue: speech.pending.filter(function (seg) { return seg.seq > speech.acked; }),
      knockSeq: knockSeq,
      auto: {
        running: auto.running,
        targetCount: auto.targetCount,
        currentCount: auto.currentCount,
        intervalMs: auto.intervalMs,
        cycleTitle: auto.cycleTitle
      },
      records: stats.records
    };
  }

  function jsonResponse(payload, status) {
    var code = status || 200;
    var text = JSON.stringify(payload);
    if (typeof Response === 'function') {
      try {
        return new Response(text, { status: code, headers: { 'content-type': 'application/json' } });
      } catch (e) {}
    }
    return {
      ok: code >= 200 && code < 300,
      status: code,
      json: function () { return Promise.resolve(payload); },
      text: function () { return Promise.resolve(text); }
    };
  }

  function handleApi(path, init) {
    var method = ((init && init.method) || 'GET').toUpperCase();
    var body = {};
    if (init && typeof init.body === 'string' && init.body) {
      try { body = JSON.parse(init.body); } catch (e) { body = {}; }
    }
    var mark = path.indexOf('?');
    if (mark >= 0) path = path.slice(0, mark);

    if (path === '/state') {
      return jsonResponse(buildState());
    }
    if (path === '/knock') {
      knockStats(clampInt(body.count, 1, 1, MAX_KNOCK_BATCH));
      return jsonResponse({ state: buildState() });
    }
    if (path === '/speech-ack') {
      if (typeof body.seq === 'number') ackSpeech(body.seq);
      if (typeof body.accompanimentIntervalMs === 'number') {
        hostPrefs.accompanimentIntervalMs = clampInt(body.accompanimentIntervalMs, 1200, 200, 60000);
        saveHostPrefs();
      }
      if (typeof body.modelUrl === 'string') {
        hostPrefs.modelUrl = body.modelUrl.trim().slice(0, 512);
        saveHostPrefs();
      }
      if (typeof body.text === 'string' && body.text.trim()) enqueueSpeech(body.text, 'tool');
      return jsonResponse({ state: buildState() });
    }
    if (path === '/tokens-per-merit') {
      var value = Number(body.value);
      if (isFinite(value) && value !== 0 && Math.abs(value) <= 10000000) {
        hostPrefs.tokensPerMerit = Math.trunc(value);
        saveHostPrefs();
      }
      return jsonResponse({ state: buildState() });
    }
    if (path === '/auto') {
      if (body.action === 'stop') stopAuto();
      else startAuto();
      return jsonResponse({ state: buildState() });
    }
    return jsonResponse({ error: 'not found', method: method, path: path }, 404);
  }

  /** 插件用 `globalThis.__DSH_TRANSPORT__.fetch`（宿主的 hostFetch 会先问这里）。 */
  window.__DSH_HOST_FETCH__ = function (url, init) {
    var path = String(url || '');
    var absolute = path.match(/^https?:\/\/[^/]+(\/.*)$/);
    if (absolute) {
      // 只接本宿主页面同源的请求，别把外网 URL 的路径当成本地接口
      var origin = location.protocol + '//' + location.host;
      if (path.indexOf(origin) !== 0) return null;
      path = absolute[1];
    }
    if (path.indexOf(API_BASE) !== 0) return null;
    return handleApi(path.slice(API_BASE.length), init || {});
  };

  // ---- 7. 会话快照 → 语音队列 / token 功德 -------------------------------------

  var lastReplySeq = 0;
  var lastTokensSeq = 0;
  var lastState = '';

  function onSessionSnapshot(snapshot) {
    if (!snapshot) return;
    if (snapshot.reply && typeof snapshot.reply === 'string' &&
        snapshot.replySeq && snapshot.replySeq !== lastReplySeq) {
      lastReplySeq = snapshot.replySeq;
      enqueueSpeech(snapshot.reply, 'llm');
      HOST.log('llm reply queued (' + snapshot.reply.length + ' chars)');
    }
    if (typeof snapshot.tokens === 'number' && snapshot.tokens > 0 &&
        snapshot.tokensSeq && snapshot.tokensSeq !== lastTokensSeq) {
      lastTokensSeq = snapshot.tokensSeq;
      consumeTokens(snapshot.tokens);
      HOST.log('tokens fed: ' + snapshot.tokens);
    }
    var state = snapshot.state || '';
    if (state === 'THINKING' && (lastState === '' || lastState === 'IDLE' ||
        lastState === 'SUCCESS' || lastState === 'ERROR')) {
      enqueueSpeech(snapshot.message || '我想想…', 'tool');
    }
    if (state === 'WAITING' && lastState !== 'WAITING') {
      enqueueSpeech(snapshot.message || '需要你点一下：工具要执行', 'tool');
    }
    if (state === 'ERROR' && lastState !== 'ERROR') {
      enqueueSpeech(snapshot.message || '出错了，看一眼', 'tool');
    }
    if (state === 'SUCCESS' && lastState !== 'SUCCESS' && !snapshot.reply) {
      enqueueSpeech(snapshot.message || '这轮做完了！', 'tool');
    }
    lastState = state;
  }

  HOST.onSession(onSessionSnapshot);

  // ---- 8. 按住「拖动 pill」单指拖窗口（双指拖窗口由 native 兜底） -----------------

  var drag = null;

  function closestDragHandle(node) {
    while (node && node !== document) {
      if (node.classList && node.classList.contains('dsh-whale-drag-handle')) return node;
      node = node.parentNode;
    }
    return null;
  }

  document.addEventListener('touchstart', function (event) {
    if (event.touches.length !== 1) return;
    if (!closestDragHandle(event.target)) return;
    var t = event.touches[0];
    drag = { x: t.screenX, y: t.screenY, moved: false };
  }, { passive: true });

  document.addEventListener('touchmove', function (event) {
    if (!drag || event.touches.length !== 1) return;
    var t = event.touches[0];
    var dx = t.screenX - drag.x;
    var dy = t.screenY - drag.y;
    if (!dx && !dy) return;
    drag.x = t.screenX;
    drag.y = t.screenY;
    drag.moved = true;
    try { HOST.native.moveWindowBy(dx, dy); } catch (e) {}
    if (event.cancelable) event.preventDefault();
  }, { passive: false });

  function finishDrag() {
    if (!drag) return;
    var moved = drag.moved;
    drag = null;
    if (moved) {
      try { HOST.native.endDrag(); } catch (e) {}
    }
  }

  document.addEventListener('touchend', finishDrag, { passive: true });
  document.addEventListener('touchcancel', finishDrag, { passive: true });

  // ---- 9. 设置面板打开 → 临时加窗；收起 → 缩窗（上游面板 356×78vh 塞不进 300dp） --

  function watchSettingsPanel() {
    var backdrop = document.querySelector('.dsh-whale-modal-backdrop');
    if (!backdrop) {
      setTimeout(watchSettingsPanel, 400);
      return;
    }
    var open = false;
    var apply = function () {
      var isOpen = backdrop.style.display === 'flex';
      if (isOpen === open) return;
      open = isOpen;
      if (open) HOST.setContentSize(SIZE_PANEL[0], SIZE_PANEL[1]);
      else HOST.setContentSize(currentBaseSize()[0], currentBaseSize()[1]);
    };
    try {
      new MutationObserver(apply).observe(backdrop, { attributes: true, attributeFilter: ['style'] });
    } catch (e) {}
    apply();
  }

  function watchCollapse() {
    var container = document.getElementById('dsh-whale-floating-pet');
    if (!container) {
      setTimeout(watchCollapse, 400);
      return;
    }
    var collapsed = container.classList.contains('is-collapsed');
    var apply = function () {
      var next = container.classList.contains('is-collapsed');
      if (next === collapsed) return;
      collapsed = next;
      var base = currentBaseSize();
      HOST.setContentSize(base[0], base[1]);
    };
    try {
      new MutationObserver(apply).observe(container, { attributes: true, attributeFilter: ['class'] });
    } catch (e) {}
  }

  function currentBaseSize() {
    var container = document.getElementById('dsh-whale-floating-pet');
    if (container && container.classList.contains('is-collapsed')) return SIZE_COLLAPSED;
    return SIZE_DEFAULT;
  }

  // ---- 10. Android WebView 没有 Web Speech：预置 voiceEnabled=false --------------
  // 走上游 bundle.ts 自带的「静音也要显示 + 敲一下」降级路径（每句 900ms、有说话动作），
  // 而不是 speakSentence 立刻 onEnd（气泡一闪而过、看不到口型）。
  // 必须在插件 client 的 apply() 之前写进 localStorage —— 本文件先于插件入口执行。
  if (!('speechSynthesis' in window)) {
    try {
      var CLIENT_PREFS_KEY = 'dsh_whale_client_prefs';
      var clientPrefs = JSON.parse(localStorage.getItem(CLIENT_PREFS_KEY) || 'null');
      if (!clientPrefs || typeof clientPrefs !== 'object') clientPrefs = {};
      if (typeof clientPrefs.voiceEnabled !== 'boolean') {
        clientPrefs.voiceEnabled = false;
        localStorage.setItem(CLIENT_PREFS_KEY, JSON.stringify(clientPrefs));
      }
    } catch (e) {}
  }

  // ---- 11. 调试钩子（CDP 探针/回归用，不参与正常交互） --------------------------

  window.__whaleStateJson = function () { return JSON.stringify(buildState()); };
  window.__whaleKnock = function (count) {
    knockStats(clampInt(count, 1, 1, MAX_KNOCK_BATCH));
    return JSON.stringify(buildState());
  };
  window.__whaleSpeak = function (text) { return enqueueSpeech(String(text || ''), 'tool'); };
  window.__whaleFeedTokens = function (tokens) { return JSON.stringify(statsOf(consumeTokens(tokens))); };

  watchSettingsPanel();
  watchCollapse();
  // 先把窗口收到挂件的包围盒（native 在加窗口时已经按「有适配层」选了可触摸模式，
  // 这次调用只是把尺寸对齐；重复调用无害）。
  try {
    HOST.native.setPassThrough(false);
    HOST.setContentSize(SIZE_DEFAULT[0], SIZE_DEFAULT[1]);
  } catch (e) {}
  HOST.log('whale host adapter ready (api ' + API_BASE + ')');
})();
