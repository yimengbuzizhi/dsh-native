(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var pluginId = params.get('plugin') || '';
  var entryPath = params.get('entry') || 'lib/client.js';
  var pluginName = params.get('name') || pluginId;
  var containerKind = params.get('container') || 'floating';
  var ORIGIN = 'https://' + location.host;
  var PLUGIN_BASE = ORIGIN + '/plugin';

  var native = window.DSHNative || {
    state: function () { return '{}'; },
    info: function () { return '{}'; },
    log: function () {},
    ready: function () {},
    moveWindowBy: function () {},
    endDrag: function () {},
    collapse: function () {},
    openApp: function () {},
    kvGet: function () { return ''; },
    kvSet: function () {},
    setContentSize: function () {},
    setPassThrough: function () {},
    container: function () { return '{}'; },
    setTitle: function () {},
    closeSelf: function () {},
    postMessage: function () {},
    theme: function () { return '{}'; },
    device: function () { return '{}'; }
  };

  function log(message) {
    try { native.log(String(message)); } catch (e) {}
  }

  function showStatus(message) {
    var box = document.getElementById('ds-status');
    if (!box) return;
    box.style.display = 'block';
    box.textContent = String(message);
    log('status: ' + message);
  }

  // ---- P3-e 容器能力（docs/187 §9.2）：关闭自己 / 设标题 / 发消息 / 读主题 / 设备能力走桥 ----
  // 只有这 5 个；没有 slot 注册、没有会话流内嵌（红线）。插件拿不到原生里的任何私有数据：
  // theme/device 只给暗色开关与设备事实，postMessage 只往宿主送一条有界字符串。
  var containerInfo = { kind: containerKind, pluginId: pluginId, name: pluginName, title: pluginName, capabilities: [] };
  try {
    var bootContainer = JSON.parse(native.container ? native.container() : '{}');
    if (bootContainer && bootContainer.kind) containerInfo = bootContainer;
  } catch (e) {}
  var containerMessageHandlers = [];

  window.__DSH_CONTAINER__ = {
    info: function () { return containerInfo; },
    kind: containerInfo.kind || containerKind,
    close: function () {
      try { native.closeSelf(); } catch (e) { log('closeSelf failed: ' + (e && e.message || e)); }
    },
    setTitle: function (title) {
      containerInfo.title = String(title == null ? '' : title).slice(0, 80);
      try { native.setTitle(containerInfo.title); } catch (e) { log('setTitle failed: ' + (e && e.message || e)); }
    },
    postMessage: function (message) {
      var text = typeof message === 'string' ? message : JSON.stringify(message);
      try { native.postMessage(String(text).slice(0, 4000)); } catch (e) { log('postMessage failed: ' + (e && e.message || e)); }
    },
    theme: function () {
      try { return JSON.parse(native.theme ? native.theme() : '{}'); } catch (e) { return {}; }
    },
    device: function () {
      try { return JSON.parse(native.device ? native.device() : '{}'); } catch (e) { return {}; }
    },
    /** 宿主 → 页面的消息（原生侧 postMessage 的应答/以后的事件通道）。 */
    onMessage: function (handler) {
      if (typeof handler !== 'function') return function () {};
      containerMessageHandlers.push(handler);
      return function () {
        var index = containerMessageHandlers.indexOf(handler);
        if (index >= 0) containerMessageHandlers.splice(index, 1);
      };
    }
  };

  window.__containerMessage = function (json) {
    var value = json;
    try { value = JSON.parse(json); } catch (e) {}
    containerMessageHandlers.slice().forEach(function (fn) {
      try { fn(value); } catch (e) { log('container message handler error: ' + (e && e.message || e)); }
    });
  };

  // ---- 全局对象：DSH 图片代理桥指向本插件的安装目录（/media/... 由宿主剥前缀） ----
  window.__DSH_PROXY_BRIDGE__ = PLUGIN_BASE;

  // ---- 宿主适配层 API（M72）：`assets/client-host/adapters/<插件id>.js` 用 ----
  // 插件在桌面端依赖 Node 宿主（`ctx.webServer` 的 `/dsh-whale/*` 之类）；Android 没有 Node，
  // 由宿主适配层在页面里补同语义实现。这里只暴露**通用**能力，不暴露 App 的 SharedPreferences：
  //   kvGet/kvSet（native 落盘，键前缀由 native 加插件 id）、setContentSize（字面量 = CSS px ≈ dp）、
  //   会话快照订阅、日志。
  var sessionHandlers = [];
  window.__DSH_HOST__ = {
    pluginId: pluginId,
    native: native,
    log: log,
    kvGet: function (key) {
      try { return String(native.kvGet(String(key)) || ''); } catch (e) { return ''; }
    },
    kvSet: function (key, value) {
      try { native.kvSet(String(key), String(value)); } catch (e) {}
    },
    setContentSize: function (widthDp, heightDp) {
      try { native.setContentSize(Number(widthDp) || 0, Number(heightDp) || 0); } catch (e) {}
    },
    currentSession: function () { return snapshot; },
    onSession: function (handler) {
      if (typeof handler !== 'function') return function () {};
      sessionHandlers.push(handler);
      try { handler(snapshot); } catch (e) { log('host session handler error: ' + (e && e.message || e)); }
      return function () {
        var index = sessionHandlers.indexOf(handler);
        if (index >= 0) sessionHandlers.splice(index, 1);
      };
    }
  };

  // ---- 网络出口：`__DSH_TRANSPORT__` + `window.fetch`（M72） ----
  // DSH 的浏览器接线里有个 `globalThis.__DSH_TRANSPORT__`，插件（whale）优先用它。
  // 宿主先给适配层一次机会（`window.__DSH_HOST_FETCH__(url, init)` 返回 Response 形状就采用，
  // 返回 null 表示不接），没接的 URL 原样走网络。
  var realFetch = typeof window.fetch === 'function' ? window.fetch.bind(window) : null;
  function hostFetch(input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var handler = window.__DSH_HOST_FETCH__;
    if (typeof handler === 'function' && url) {
      var handled = null;
      try { handled = handler(url, init || {}); } catch (e) {
        log('host fetch handler error: ' + (e && e.message || e));
      }
      if (handled && typeof handled.then === 'function') return handled;
      if (handled) return Promise.resolve(handled);
    }
    if (realFetch) return realFetch(input, init);
    return Promise.reject(new Error('fetch unavailable'));
  }
  window.__DSH_TRANSPORT__ = { fetch: hostFetch };
  window.fetch = hostFetch;

  // ---- 触摸区域上报：只让插件 UI 真正占用的矩形接收触摸，其余穿透到下层 App ----
  // 透明悬浮窗如果整块可点，宠物旁边的聊天区/输入框都会没反应（用户 2026-11-10 反馈）。
  // 这里把所有可见、可交互的元素矩形并集报给 native，native 用 TOUCHABLE_INSETS_REGION
  // 把窗口的输入区域收窄；DOM 变化/动画移动时重新上报。
  var lastRegionJson = '';
  function reportTouchRegion() {
    if (!native.setTouchRegion) return;
    var vw = window.innerWidth, vh = window.innerHeight;
    var rects = [];
    var nodes = document.querySelectorAll('body *');
    for (var i = 0; i < nodes.length; i += 1) {
      var el = nodes[i];
      if (el === document.body || el === document.documentElement) continue;
      if (el.id === 'ds-root' || el.id === 'ds-slots') continue;
      // slot 挂载盒（div[data-slot]，index.html 里给了视口大小的绝对定位 + pointer-events:none）
      // 只是插件的容器，不是可见内容本身：它的整屏 rect 绝不能报给原生，否则悬浮窗/小窗会
      // 把下层 App 的触摸全挡住。这里显式跳过（不依赖 CSS 是否生效）；插件真正渲染的内容
      // 节点仍是它的子节点，照常参与上报。
      if (el.getAttribute && el.getAttribute('data-slot') !== null) continue;
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (Number(cs.opacity) === 0) continue;
      if (cs.pointerEvents === 'none') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 3 || r.height < 3) continue;
      var left = Math.max(0, r.left), top = Math.max(0, r.top);
      var right = Math.min(vw, r.right), bottom = Math.min(vh, r.bottom);
      if (right - left < 3 || bottom - top < 3) continue;
      rects.push([Math.round(left), Math.round(top), Math.round(right - left), Math.round(bottom - top)]);
    }
    var json = JSON.stringify(rects);
    if (json === lastRegionJson) return;
    lastRegionJson = json;
    try { native.setTouchRegion(json); } catch (e) {}
  }

  var regionTimer = 0;
  function scheduleRegion() {
    if (regionTimer) return;
    regionTimer = setTimeout(function () { regionTimer = 0; reportTouchRegion(); }, 120);
  }

  // ---- 会话面：BigFish 快照（running/nodes/pending） ----
  var snapshot = { running: false, nodes: [], pending: [], sessionId: 'dsh-native' };
  try {
    var bootState = JSON.parse(native.state() || '{}');
    if (bootState && bootState.session) snapshot = bootState.session;
  } catch (e) {}
  var listeners = [];

  var sessionFace = {
    sessionId: 'dsh-native',
    getSnapshot: function () { return snapshot; },
    subscribe: function (fn) {
      listeners.push(fn);
      return function () {
        var index = listeners.indexOf(fn);
        if (index >= 0) listeners.splice(index, 1);
      };
    }
  };
  // ---- 当前会话的标准 provide 投影（ctx.sessions.currentProvideInfo）----
  // 上游契约（@deepseek-ai/dsh-client-ui-slots SessionMaybeProvideInfo）：
  //   { sessionId, hooks: { session?: HostObservable<ConversationSnapshot> }, props: {} }
  // host 只有一份原生会话快照，这里映射成同形状。ConversationSnapshot.views 用上游空实现
  // `{ get: () => undefined }`（runtime 里的 EMPTY_CONVERSATION_VIEWS）：pet 的 activity
  // 视图读不到数据时按「无活动 = resting」走，不会因为缺 get/Map 抛错。
  var EMPTY_CONVERSATION_VIEWS = { get: function () { return undefined; } };
  var cachedConversationSource = null;
  var cachedConversationSnapshot = null;
  function conversationSnapshotOf() {
    if (cachedConversationSource !== snapshot) {
      cachedConversationSource = snapshot;
      cachedConversationSnapshot = {
        sessionId: snapshot.sessionId,
        running: snapshot.running,
        nodes: snapshot.nodes,
        pending: snapshot.pending,
        views: EMPTY_CONVERSATION_VIEWS
      };
    }
    return cachedConversationSnapshot;
  }
  var sessionSnapshotListeners = [];
  var sessionSnapshotObservable = {
    getSnapshot: conversationSnapshotOf,
    subscribe: function (fn) {
      sessionSnapshotListeners.push(fn);
      return function () {
        var index = sessionSnapshotListeners.indexOf(fn);
        if (index >= 0) sessionSnapshotListeners.splice(index, 1);
      };
    }
  };
  var provideListeners = [];
  var provideInfo = null;
  var provideInfoSessionId;
  function currentProvideInfoOf() {
    var sessionId = snapshot ? snapshot.sessionId : undefined;
    if (provideInfo === null || provideInfoSessionId !== sessionId) {
      provideInfoSessionId = sessionId;
      provideInfo = {
        sessionId: sessionId,
        hooks: { session: sessionId === undefined ? undefined : sessionSnapshotObservable },
        props: {}
      };
    }
    return provideInfo;
  }
  var currentProvideInfo = {
    getSnapshot: currentProvideInfoOf,
    subscribe: function (fn) {
      provideListeners.push(fn);
      return function () {
        var index = provideListeners.indexOf(fn);
        if (index >= 0) provideListeners.splice(index, 1);
      };
    }
  };

  var sessionListListeners = [];
  // 与上游 SessionListState 对齐（runtime-005 sessions/service.d.ts:67-84）：
  // 宿主快照只有当前会话 id，没有全量会话表，所以 ids/byId 目前只表达"当前会话"，
  // phase 恒 ready；但字段齐全 + subscribe 会通知，避免 useSessions 渲染期崩/永不更新。
  var sessionListSnapshot = {
    ids: [], byId: {}, current: snapshot && snapshot.sessionId, phase: 'ready',
    subagentsByParent: {}, jobsBySession: {}, currentAddress: undefined
  };
  var sessions = {
    list: {
      getSnapshot: function () { return sessionListSnapshot; },
      subscribe: function (fn) {
        if (typeof fn !== 'function') return function () {};
        sessionListListeners.push(fn);
        return function () {
          var index = sessionListListeners.indexOf(fn);
          if (index >= 0) sessionListListeners.splice(index, 1);
        };
      }
    },
    binding: function () { return { session: sessionFace }; },
    scope: function () { return sessionFace; },
    active: function () { return snapshot && snapshot.sessionId; },
    // 上游 `ctx.sessions.currentProvideInfo`：HostObservable<SessionMaybeProvideInfo>。
    currentProvideInfo: currentProvideInfo
  };

  window.__clientSession = function (json) {
    var previousSessionId = snapshot && snapshot.sessionId;
    try { snapshot = JSON.parse(json); } catch (e) { return; }
    listeners.slice().forEach(function (fn) {
      try { fn(); } catch (e) { log('session listener error: ' + (e && e.message || e)); }
    });
    sessionHandlers.slice().forEach(function (fn) {
      try { fn(snapshot); } catch (e) { log('host session handler error: ' + (e && e.message || e)); }
    });
    // 会话内容变了：conversation snapshot 的观察者（含 pet 的 activity source）重采样。
    sessionSnapshotListeners.slice().forEach(function (fn) {
      try { fn(); } catch (e) { log('conversation snapshot listener error: ' + (e && e.message || e)); }
    });
    // 只有切换会话才换 provide 面（上游语义：selection/provider-roster 变化才 publish）。
    if (previousSessionId !== snapshot.sessionId) {
      sessionListSnapshot = {
        ids: [], byId: {}, current: snapshot.sessionId, phase: 'ready',
        subagentsByParent: {}, jobsBySession: {}, currentAddress: undefined
      };
      sessionListListeners.slice().forEach(function (fn) {
        try { fn(); } catch (e) { log('session list listener error: ' + (e && e.message || e)); }
      });
      provideListeners.slice().forEach(function (fn) {
        try { fn(); } catch (e) { log('provide info listener error: ' + (e && e.message || e)); }
      });
    }
  };

  // ---- 浏览器运行时兼容层：@deepseek-ai/dsh-client-runtime/client ----
  // 真包 lib/client.js 的 require 白名单实际只有三个字符串（已 grep 全 bundle）：
  //   "react" / "react/jsx-runtime" / "@deepseek-ai/dsh-client-runtime/client"
  // 且只解构 defineStore（见 pet-store.ts）。这里按上游 @deepseek-ai/dsh-client-runtime
  // 0.0.1-rc 的真实契约实现（语义取自其 lib/types/client/contract/store.d.ts + lib/client.js）：
  //   defineStore(decl) -> { spec, create(scopeKey?) -> { actions, getSnapshot, subscribe, clearPersisted } }
  // actions 是 (draft, ...params) => void 的纯变异函数，调用时按 immer 语义产出新 state；
  // persist 是 localStorage 键（session scope 追加 ".<scopeKey>"，与上游一致）。
  // 没有导出任何假函数：本层只导出真正实现了语义的符号（defineStore / createSnapshotStore / shallowEqual）。
  function noop() { return undefined; }
  function sameValue(a, b) { return a === b || (a !== a && b !== b); }
  function shallowEqual(a, b) {
    if (sameValue(a, b)) return true;
    if (typeof a !== 'object' || a === null || typeof b !== 'object' || b === null) return false;
    var keysA = Object.keys(a);
    var keysB = Object.keys(b);
    if (keysA.length !== keysB.length) return false;
    for (var i = 0; i < keysA.length; i += 1) {
      if (!Object.prototype.hasOwnProperty.call(b, keysA[i]) || !sameValue(a[keysA[i]], b[keysA[i]])) return false;
    }
    return true;
  }
  function jsonEqual(a, b) {
    try { return JSON.stringify(a) === JSON.stringify(b); } catch (e) { return false; }
  }
  /** JSON 深拷贝当 draft（上游用 immer；插件 state 都是可 JSON 化的纯数据）。 */
  function cloneState(value) {
    if (value === null || typeof value !== 'object') return value;
    try { return JSON.parse(JSON.stringify(value)); } catch (e) { return value; }
  }
  function createSnapshotStore(init, persistName) {
    var state = init;
    var listeners = [];
    function persist() {
      if (!persistName) return;
      try { window.localStorage.setItem(persistName, JSON.stringify(state)); } catch (e) {
        log('store persist failed: ' + (e && e.message || e));
      }
    }
    function notify() {
      listeners.slice().forEach(function (fn) {
        try { fn(); } catch (e) { log('store listener error: ' + (e && e.message || e)); }
      });
    }
    if (persistName) {
      // 与上游 attachPersistence 一致：读失败只禁用持久化，不影响 store 本体。
      try {
        var raw = window.localStorage.getItem(persistName);
        if (raw !== null) state = JSON.parse(raw);
      } catch (e) { log('store rehydrate failed: ' + (e && e.message || e)); }
    }
    return {
      getSnapshot: function () { return state; },
      subscribe: function (fn) {
        if (typeof fn !== 'function') return noop;
        listeners.push(fn);
        return function () {
          var index = listeners.indexOf(fn);
          if (index >= 0) listeners.splice(index, 1);
        };
      },
      update: function (mutator) {
        if (typeof mutator !== 'function') return;
        var draft = cloneState(state);
        mutator(draft);
        if (jsonEqual(state, draft)) return;
        state = draft;
        persist();
        notify();
      },
      set: function (next) {
        if (jsonEqual(state, next)) return;
        state = next;
        persist();
        notify();
      }
    };
  }
  function defineStore(decl) {
    if (!decl || typeof decl.init !== 'function') {
      throw new Error('defineStore 需要 { init, actions }');
    }
    var declActions = decl.actions || {};
    return {
      spec: decl,
      create: function (scopeKey) {
        // 上游：persist key = decl.persist（root scope）或 `${decl.persist}.${scopeKey}`（session scope）。
        var persistKey = decl.persist === undefined ? undefined
          : (scopeKey === undefined ? decl.persist : String(decl.persist) + '.' + String(scopeKey));
        var store = createSnapshotStore(decl.init(), persistKey);
        var actions = {};
        Object.keys(declActions).forEach(function (key) {
          var mutate = declActions[key];
          if (typeof mutate !== 'function') return;
          actions[key] = function () {
            var params = Array.prototype.slice.call(arguments);
            store.update(function (draft) {
              mutate.apply(null, [draft].concat(params));
            });
          };
        });
        return {
          actions: actions,
          getSnapshot: store.getSnapshot,
          subscribe: store.subscribe,
          store: store,
          clearPersisted: function () {
            if (persistKey === undefined || persistKey === null) return;
            try { window.localStorage.removeItem(persistKey); } catch (e) {}
          }
        };
      }
    };
  }
  /** require('@deepseek-ai/dsh-client-runtime/client') 的导出面（只放真实现）。 */
  var clientRuntime = {
    defineStore: defineStore,
    createSnapshotStore: createSnapshotStore,
    shallowEqual: shallowEqual
  };

  // ---- locale 服务（ctx.locale）：register(ns,{zh,en}) -> disposer；t(ns,key) -> 文案 ----
  // 上游字典键不带命名空间（如 'action.show'），这里同时兼容 "<ns>.<key>" 形式；
  // 找不到时回退 key 本身，绝不抛错。
  var localeDictionaries = {};
  function activeLanguage() {
    var nav = window.navigator || {};
    var language = String(nav.language || nav.userLanguage || 'en').toLowerCase();
    return language.indexOf('zh') === 0 ? 'zh' : 'en';
  }
  var locale = {
    language: activeLanguage,
    register: function (namespace, dictionaries) {
      var ns = String(namespace == null ? '' : namespace);
      var entry = { zh: {}, en: {} };
      if (dictionaries && typeof dictionaries === 'object' && (dictionaries.zh || dictionaries.en)) {
        entry.zh = dictionaries.zh || {};
        entry.en = dictionaries.en || {};
      } else if (dictionaries && typeof dictionaries === 'object') {
        // 单语种字典：当成 zh/en 共用。
        entry.zh = dictionaries;
        entry.en = dictionaries;
      }
      localeDictionaries[ns] = entry;
      return function () {
        if (localeDictionaries[ns] === entry) delete localeDictionaries[ns];
      };
    },
    t: function (namespace, key) {
      var ns = String(namespace == null ? '' : namespace);
      var name = String(key == null ? '' : key);
      var entry = localeDictionaries[ns];
      if (entry) {
        var dict = entry[activeLanguage()] || entry.zh || entry.en || {};
        if (Object.prototype.hasOwnProperty.call(dict, name)) return String(dict[name]);
        var qualified = ns ? ns + '.' + name : name;
        if (Object.prototype.hasOwnProperty.call(dict, qualified)) return String(dict[qualified]);
        // 最后在另一语言里兜底，避免中文设备缺 en 字典时全空。
        var other = entry.zh === dict ? entry.en : entry.zh;
        if (other && Object.prototype.hasOwnProperty.call(other, name)) return String(other[name]);
      }
      return name;
    }
  };

  // ---- conversationEvents / conversationViews 服务 ----
  // host 没有 host→client 的会话事件流水线（原生只给渲染后的快照），所以只**登记定义**并返回
  // disposer，不假装在推进事件流。pet 的 registerPetActivityView 只要求这两个服务存在；
  // activity 的真实来源（views.get('pet-activity')）由上面的 EMPTY_CONVERSATION_VIEWS 安全兜底。
  var conversationEventDefinitions = [];
  var conversationViewDefinitions = {};
  var conversationEvents = {
    register: function (definition) {
      conversationEventDefinitions.push(definition);
      return function () {
        var index = conversationEventDefinitions.indexOf(definition);
        if (index >= 0) conversationEventDefinitions.splice(index, 1);
      };
    },
    definitions: function () { return conversationEventDefinitions.slice(); },
    snapshot: function () { return { events: conversationEventDefinitions.length, views: Object.keys(conversationViewDefinitions) }; }
  };
  var conversationViews = {
    register: function (definition) {
      var target = definition && definition.target != null ? String(definition.target) : 'view-' + String(Object.keys(conversationViewDefinitions).length);
      conversationViewDefinitions[target] = definition;
      return function () {
        if (conversationViewDefinitions[target] === definition) delete conversationViewDefinitions[target];
      };
    },
    get: function (target) { return conversationViewDefinitions[String(target)]; },
    snapshot: function () { return Object.keys(conversationViewDefinitions); }
  };

  // ---- require 白名单：React 18 UMD + 两个 shim ----
  function jsxElement(type, props, key) {
    var p = Object.assign({}, props || {});
    if (key !== undefined) p.key = key;
    var children = p.children;
    delete p.children;
    if (children === undefined) return window.React.createElement(type, p);
    if (Array.isArray(children)) {
      return window.React.createElement.apply(null, [type, p].concat(children));
    }
    return window.React.createElement(type, p, children);
  }
  var jsxRuntime = {
    Fragment: window.React.Fragment,
    jsx: jsxElement,
    jsxs: jsxElement,
    jsxDEV: jsxElement
  };

  function requireModule(name) {
    switch (name) {
      case 'react':
        return window.React;
      case 'react-dom':
        return window.ReactDOM;
      case 'react-dom/client':
        return {
          createRoot: function (element, options) { return window.ReactDOM.createRoot(element, options); },
          hydrateRoot: function (element, node, options) {
            return window.ReactDOM.hydrateRoot(element, node, options);
          }
        };
      case 'react/jsx-runtime':
      case 'react/jsx-dev-runtime':
        return jsxRuntime;
      case '@deepseek-ai/dsh-client-runtime':
      case '@deepseek-ai/dsh-client-runtime/client':
        return clientRuntime;
      default:
        // 通用兜底：同一运行时的其它子路径（如 /invariant）也要能 require；
        // 真实存在的符号在本层实现，没有的返回 undefined（plugins 自己会看到缺符号），
        // 不在这里伪造函数骗过插件。
        if (name.indexOf('@deepseek-ai/dsh-client-runtime/') === 0) return clientRuntime;
        throw new Error('宿主未提供模块：' + name);
    }
  }

  // ---- slots 最小实现（shell.overlay 渲染成整页层；settings.* 先不渲染） ----
  // 上游语义（@deepseek-ai/dsh-client-ui-slots）：
  //   - register(spec, component)：spec 可带 name/id/order/locale/store/inject；
  //   - inject(name, cb)：该 slot 有声明（渲染方）时才执行 cb；否则排队等声明。
  //   - 组件 props 由四份 share 组合：PropsRuntime（owner/标准 kit）+ PropsStore（useStore+actions）
  //     + InjectFace（hooks → use<Name>）+ PropsLocale（t）；本层按最小面组装。
  // 真实 bug（p4 §3）：pet 在 apply 里 `inject('shell.overlay', () => register(...))`，host 从不
  // 宣告 shell.overlay 已可用，队列永远没人 flush → 组件根本不注册。announceSlot() 负责补这一步。
  var slotRegistrations = [];
  var slotRegistrationSeq = 0;
  var slotInjects = {};
  var slotRoots = {};
  var slotMounts = {};
  var declaredSlots = {};

  function removeItem(list, item) {
    if (!list) return;
    var index = list.indexOf(item);
    if (index >= 0) list.splice(index, 1);
  }

  /**
   * 宣告某个 slot 已有渲染方，并 flush 它的 inject 队列。
   * 先把整条队列从表里摘掉再调用：callback 内部通常会 register()，register() 内也可能
   * flush 同名队列，若不先摘会递归/重复执行。重复 announce 幂等。
   */
  function flushSlotInjects(name) {
    var pending = slotInjects[name];
    if (!pending || !pending.length) return;
    delete slotInjects[name];
    pending.slice().forEach(function (item) {
      if (!item || item.disposed) return;
      try {
        var disposer = item.fn();
        if (typeof disposer === 'function') item.dispose = disposer;
      } catch (e) {
        log('slot inject failed: ' + (e && e.message || e));
      }
    });
  }

  function announceSlot(name) {
    declaredSlots[String(name)] = true;
    flushSlotInjects(String(name));
  }

  // 组件侧 selector hook：HostObservable + selector → useSyncExternalStore（无此 API 时退化
  // useState+useEffect）。返回缓存让 uSES 的 getSnapshot 稳定，避免无限重渲染。
  function useObservableFallback(observable, selector) {
    var React = window.React;
    var version = React.useState(0);
    var forceUpdate = version[1];
    React.useEffect(function () {
      return observable.subscribe(function () { forceUpdate(function (n) { return n + 1; }); });
    }, [observable]);
    var value = observable.getSnapshot();
    return typeof selector === 'function' ? selector(value) : value;
  }

  function useObservable(observable, selector, eq) {
    var React = window.React;
    if (!observable || typeof observable.getSnapshot !== 'function') {
      // 静态值（非 observable 的 hooks 成员）：保持 hook 形状但不订阅。
      return typeof selector === 'function' ? selector(undefined) : undefined;
    }
    if (typeof React.useSyncExternalStore !== 'function') {
      return useObservableFallback(observable, selector);
    }
    var subscribe = React.useCallback(function (onStoreChange) {
      return observable.subscribe(onStoreChange);
    }, [observable]);
    var cache = React.useRef({ has: false, snapshot: null, value: undefined });
    var selectorRef = React.useRef(selector);
    selectorRef.current = selector;
    var eqRef = React.useRef(eq);
    eqRef.current = eq;
    var getSnapshot = React.useCallback(function () {
      var next = observable.getSnapshot();
      var current = cache.current;
      if (current.has && current.snapshot === next) return current.value;
      var value = typeof selectorRef.current === 'function' ? selectorRef.current(next) : next;
      if (current.has && typeof eqRef.current === 'function' && eqRef.current(current.value, value)) {
        current.snapshot = next;
        return current.value;
      }
      cache.current = { has: true, snapshot: next, value: value };
      return value;
    }, [observable]);
    return React.useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  }

  /** hooks 成员若本身不是 HostObservable，就包成静态 observable（hook 形状不变）。 */
  function asObservable(value) {
    if (value && typeof value.getSnapshot === 'function' && typeof value.subscribe === 'function') return value;
    return {
      getSnapshot: function () { return value; },
      subscribe: function () { return noop; }
    };
  }

  var EMPTY_WORKSPACES_SNAPSHOT = { current: undefined, list: [], byId: {} };
  var emptyWorkspacesObservable = {
    getSnapshot: function () { return EMPTY_WORKSPACES_SNAPSHOT; },
    subscribe: function () { return noop; }
  };

  /** 按 spec 组出组件 props（PropsRuntime 最小面 + store + inject + locale）。 */
  function buildSlotProps(reg) {
    var props = {
      // PropsRuntime 的最小可辨识面：真实上游是 owner/标准 kit 的展开，这里保留 slot/id 供组件/调试用。
      runtime: { slot: reg.name, id: reg.id }
    };
    var instance = reg.storeInstance;
    if (instance && typeof instance.getSnapshot === 'function') {
      props.useStore = function (selector, eq) { return useObservable(instance, selector, eq); };
      props.actions = instance.actions || {};
    }
    var face = reg.injectFace || {};
    Object.keys(face).forEach(function (key) {
      if (key === 'hooks') return;
      props[key] = face[key];
    });
    var hooks = face.hooks;
    if (hooks && typeof hooks === 'object') {
      Object.keys(hooks).forEach(function (name) {
        var source = asObservable(hooks[name]);
        var propName = 'use' + name.charAt(0).toUpperCase() + name.slice(1);
        props[propName] = function (selector, eq) { return useObservable(source, selector, eq); };
      });
    }
    if (reg.locale !== undefined && reg.locale !== null && reg.locale !== '') {
      var namespace = String(reg.locale);
      props.t = function (key) { return locale.t(namespace, key); };
    }
    // 全局标准 kit（上游 GlobalStandardProps）：root scope 组件总是收到这两个选择器 hook。
    props.useSessions = function (selector, eq) { return useObservable(sessions.list, selector, eq); };
    props.useWorkspaces = function (selector, eq) { return useObservable(emptyWorkspacesObservable, selector, eq); };
    return props;
  }

  function renderSlots() {
    var host = document.getElementById('ds-slots');
    if (!host) return;
    var seen = {};
    slotRegistrations.slice().sort(function (a, b) { return (a.order || 0) - (b.order || 0); })
      .forEach(function (reg) {
        if (reg.name !== 'shell.overlay') return;
        seen[reg.key] = true;
        if (!slotRoots[reg.key]) {
          var mount = document.createElement('div');
          mount.dataset.slot = reg.name;
          mount.dataset.slotId = reg.id;
          host.appendChild(mount);
          slotMounts[reg.key] = mount;
          slotRoots[reg.key] = window.ReactDOM.createRoot(mount);
        }
        try {
          slotRoots[reg.key].render(window.React.createElement(reg.component, buildSlotProps(reg)));
        } catch (e) {
          log('slot render failed: ' + (e && e.message || e));
        }
      });
    Object.keys(slotRoots).forEach(function (key) {
      if (seen[key]) return;
      try { slotRoots[key].unmount(); } catch (e) {}
      // 卸载后把挂载 div 也摘掉：否则反复 register/dispose 会在 #ds-slots 里累积空盒，
      // 每个空盒都是 inset:0 的定位盒（虽 pointer-events:none，仍会拖慢布局/触摸上报遍历）。
      var mount = slotMounts[key];
      if (mount && mount.parentNode) mount.parentNode.removeChild(mount);
      delete slotRoots[key];
      delete slotMounts[key];
    });
  }

  /** 解析 store 座位：上游允许 shared handle 或 exclusive factory（StoreDecl）。 */
  function resolveStoreInstance(handleOrFactory) {
    var handle = handleOrFactory;
    if (typeof handle === 'function') handle = handle();
    if (!handle) return null;
    // 上游 engine handle：{ spec, create(scopeKey?) }；root scope 的 shell.overlay 不传 scopeKey。
    if (typeof handle.create === 'function') return handle.create();
    // 直接是 StoreInstance / 裸 observable（兼容手写 store）。
    if (typeof handle.getSnapshot === 'function') return handle;
    return null;
  }

  var slots = {
    snapshot: function () {
      return slotRegistrations.map(function (reg) {
        return {
          name: reg.name, id: reg.id, order: reg.order || 0,
          locale: reg.locale || undefined, hasStore: !!reg.storeInstance,
          hooks: Object.keys((reg.injectFace && reg.injectFace.hooks) || {})
        };
      });
    },
    register: function (spec, component) {
      if (!spec || !spec.name || typeof component !== 'function') {
        throw new Error('slots.register 需要 {name} 与组件');
      }
      var reg = {
        // key 必须是每次注册唯一的身份：旧实现用 slotRegistrations.length 兜底，
        // dispose 一条后再注册会复用仍存活条目的 key → 覆盖别人的 React root（review P2-1）。
        key: String(spec.name) + '#' + (++slotRegistrationSeq),
        name: String(spec.name),
        id: String(spec.id || ''),
        order: Number(spec.order || 0),
        locale: spec.locale,
        storeInstance: null,
        injectFace: null,
        component: component
      };
      if (spec.store) {
        try { reg.storeInstance = resolveStoreInstance(spec.store); } catch (e) {
          log('slot store create failed: ' + (e && e.message || e));
          reg.storeInstance = null;
        }
      }
      if (typeof spec.inject === 'function') {
        try {
          // 上游 InjectParams：root scope 声明了 store 时，inject 的第一个参数是 baked actions。
          var injected = reg.storeInstance ? spec.inject(reg.storeInstance.actions) : spec.inject();
          reg.injectFace = injected && typeof injected === 'object' ? injected : {};
        } catch (e) {
          log('slot inject face failed: ' + (e && e.message || e));
          reg.injectFace = {};
        }
      } else {
        reg.injectFace = {};
      }
      slotRegistrations.push(reg);
      renderSlots();
      // 反向顺序（先 register 再 inject）时，register 也要补跑等待者；flush 内部已做防重入。
      flushSlotInjects(reg.name);
      return function () {
        removeItem(slotRegistrations, reg);
        renderSlots();
      };
    },
    inject: function (name, fn) {
      if (typeof fn !== 'function') throw new Error('slots.inject 需要回调');
      var slotName = String(name);
      var exists = !!declaredSlots[slotName] ||
        slotRegistrations.some(function (reg) { return reg.name === slotName; });
      if (exists) {
        try { return fn() || noop; } catch (e) {
          log('slot inject failed: ' + (e && e.message || e));
          return noop;
        }
      }
      var item = { fn: fn, disposed: false, dispose: null };
      (slotInjects[slotName] = slotInjects[slotName] || []).push(item);
      return function () {
        if (item.disposed) return;
        item.disposed = true;
        removeItem(slotInjects[slotName], item);
        if (typeof item.dispose === 'function') {
          try { item.dispose(); } catch (e) {}
          item.dispose = null;
        }
      };
    },
    // host 自己就是 shell.overlay 的渲染方；boot / window load 都会调用（幂等）。
    announce: function (name) { announceSlot(name); }
  };

  // ---- ctx：DSH client runtime 的最小面 ----
  // 真包 apply(ctx) 实际用到的服务：effect / slots / sessions / locale / conversationEvents
  // / conversationViews。缺的服务在 ctx.get() 里明确返回 undefined（不抛），未实现的 hook 面
  // 不导出假函数；这里给出的每个都是真实现（见上）。
  var disposers = [];
  var services = {
    slots: slots,
    sessions: sessions,
    locale: locale,
    conversationEvents: conversationEvents,
    conversationViews: conversationViews
  };
  var ctx = {
    get: function (name) { return services[String(name)]; },
    effect: function (fn) {
      try {
        var dispose = fn();
        if (typeof dispose === 'function') disposers.push(dispose);
        return dispose || noop;
      } catch (e) {
        log('effect error: ' + (e && e.message || e));
        return noop;
      }
    },
    on: function () { return noop; },
    slots: slots,
    sessions: sessions,
    locale: locale,
    conversationEvents: conversationEvents,
    conversationViews: conversationViews,
    logger: console,
    console: console
  };

  // ---- `__ModuleLoader__`：收 entry，等插件脚本加载完再 apply ----
  var entries = [];
  window.__ModuleLoader__ = {
    load: function (entry) {
      if (entry && typeof entry.factory === 'function') entries.push(entry);
      else log('__ModuleLoader__.load 收到非法 entry');
    },
    require: requireModule
  };

  function boot() {
    if (entries.length === 0) {
      showStatus('插件没有注册 __ModuleLoader__ entry（client bundle 不兼容）');
      return;
    }
    var entry = entries.filter(function (item) { return item.id === pluginId; })[0] || entries[0];
    var exported;
    try {
      exported = entry.factory(requireModule);
    } catch (e) {
      showStatus('插件模块执行失败：' + (e && e.message || e));
      return;
    }
    var apply = exported && (exported.apply || (exported.default && exported.default.apply));
    if (typeof apply !== 'function') {
      showStatus('插件没有导出 apply(ctx)');
      return;
    }
    try {
      var result = apply.call(exported, ctx);
      if (result && typeof result.then === 'function') result.catch(function (e) {
        showStatus('apply 异步失败：' + (e && e.message || e));
      });
    } catch (e) {
      showStatus('apply 失败：' + (e && e.message || e));
      return;
    }
    log('plugin applied: ' + pluginId + ' name=' + pluginName + ' slots=' + slotRegistrations.length);
    // host 自己就是 shell.overlay 的渲染方：apply 里 inject 排队的注册在这里补跑（幂等，
    // window load 那次再 announce 也不会重复）。真包 pet 正是靠这一步才真正 register。
    announceSlot('shell.overlay');
    try { native.ready(); } catch (e) {}
    reportTouchRegion();
    setTimeout(reportTouchRegion, 300);
    setTimeout(reportTouchRegion, 1200);
  }

  // ---- 单指触摸 → 指针/鼠标事件（插件按桌面鼠标/指针写交互） ----
  var touch = null;
  var POINTER_OF = { mousedown: 'pointerdown', mousemove: 'pointermove', mouseup: 'pointerup' };

  function dispatchPointer(type, source) {
    var pointerType = POINTER_OF[type];
    if (!pointerType || typeof window.PointerEvent !== 'function') return;
    var target = document.elementFromPoint(source.clientX, source.clientY) || document.body;
    var event = new PointerEvent(pointerType, {
      bubbles: true, cancelable: true, composed: true, view: window,
      clientX: source.clientX, clientY: source.clientY,
      screenX: source.screenX, screenY: source.screenY,
      pointerId: 1, pointerType: 'touch', isPrimary: true,
      button: 0, buttons: type === 'mouseup' ? 0 : 1
    });
    target.dispatchEvent(event);
  }

  function dispatchMouse(type, source) {
    dispatchPointer(type, source);
    var target = document.elementFromPoint(source.clientX, source.clientY) || document.body;
    var event = new MouseEvent(type, {
      bubbles: true, cancelable: true, view: window,
      clientX: source.clientX, clientY: source.clientY,
      screenX: source.screenX, screenY: source.screenY,
      button: 0, buttons: type === 'mouseup' || type === 'click' ? 0 : 1
    });
    target.dispatchEvent(event);
    return target;
  }

  /** 最近的可滚动祖先（设置面板 `max-height + overflow-y:auto` 这类需要单指滚动）。 */
  function nearestScrollable(node) {
    while (node && node !== document.body && node !== document.documentElement) {
      if (node.scrollHeight - node.clientHeight > 4) {
        var style = getComputedStyle(node);
        if (style.overflowY === 'auto' || style.overflowY === 'scroll') return node;
      }
      node = node.parentElement;
    }
    return null;
  }

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { touch = null; return; }
    var t = e.touches[0];
    var target = document.elementFromPoint(t.clientX, t.clientY) || document.body;
    touch = {
      x: t.clientX, y: t.clientY, lastY: t.clientY,
      target: target,
      scroll: nearestScrollable(target),
      startedAt: Date.now(), moved: false
    };
    dispatchMouse('mousedown', t);
    e.preventDefault();
  }, { passive: false });

  document.addEventListener('touchmove', function (e) {
    if (!touch || e.touches.length !== 1) return;
    var t = e.touches[0];
    if (Math.hypot(t.clientX - touch.x, t.clientY - touch.y) > 6) touch.moved = true;
    // 这次手势起点在可滚动面板里：单指上下拖 = 滚面板（触摸被 preventDefault 挡住了，
    // 原生滚动不会发生，所以这里自己滚）。滚动中不合成 mousemove，避免插件把它当拖拽/点击。
    if (touch.scroll) {
      var dy = t.clientY - touch.lastY;
      if (dy) {
        touch.scroll.scrollTop -= dy;
        touch.lastY = t.clientY;
      }
      e.preventDefault();
      return;
    }
    dispatchMouse('mousemove', t);
    e.preventDefault();
  }, { passive: false });

  document.addEventListener('touchend', function (e) {
    if (!touch) return;
    var t = e.changedTouches[0];
    var scrolling = !!touch.scroll && touch.moved;
    if (!scrolling) dispatchMouse('mouseup', t);
    var quick = Date.now() - touch.startedAt < 300;
    if (quick && !touch.moved) {
      dispatchMouse('click', t);
      var now = Date.now();
      if (window.__lastTapAt && now - window.__lastTapAt < 320 &&
          Math.hypot(t.clientX - window.__lastTapX, t.clientY - window.__lastTapY) < 32) {
        dispatchMouse('dblclick', t);
        window.__lastTapAt = 0;
      } else {
        window.__lastTapAt = now;
        window.__lastTapX = t.clientX;
        window.__lastTapY = t.clientY;
      }
    }
    touch = null;
    e.preventDefault();
  }, { passive: false });

  document.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  // ---- 载入插件自己的 client bundle ----
  // 顺序：宿主适配层（可选）→ 插件自己的 `dsh.client` 入口 → apply(ctx)。
  // 适配层必须先跑（它要补 `/dsh-whale/*` 这类宿主接口、预置插件 localStorage），
  // 所以这里用「先载适配层、onload/onerror 都继续载插件」的串行链，404 不影响插件。
  var safePluginId = pluginId.replace(/[^A-Za-z0-9_-]/g, '_');

  // 宿主兼容样式（可选）：只放「插件自己的布局在 WebView 里才会踩到」的补丁，
  // 例如图片还没撑开高度时 mount() 就计算吸附点。没有这个文件时 404，不影响插件。
  (function () {
    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = ORIGIN + '/host/compat/' + safePluginId + '.css';
    document.head.appendChild(link);
  })();

  function loadScript(src) {
    return new Promise(function (resolve) {
      var el = document.createElement('script');
      el.src = src;
      el.async = true;
      el.onload = function () { resolve(true); };
      el.onerror = function () { resolve(false); };
      document.body.appendChild(el);
    });
  }

  loadScript(ORIGIN + '/host/adapters/' + safePluginId + '.js')
    .then(function (loaded) {
      if (loaded) log('host adapter loaded: ' + safePluginId);
      return loadScript(PLUGIN_BASE + '/' + entryPath.split('/').map(encodeURIComponent).join('/'));
    })
    .then(function (loaded) {
      if (!loaded) {
        showStatus('载入插件入口失败：' + entryPath);
        return;
      }
      try { boot(); } catch (e) { showStatus('启动失败：' + (e && e.message || e)); }
    });

  // shell.overlay 由 host 自己渲染：document load 时再宣告一次（apply 前后两种顺序都幂等）。
  // 这样即使插件模块下载慢、load 事件先到，apply 里的 inject 也会因为 declaredSlots 立即执行。
  if (document.readyState === 'complete') {
    announceSlot('shell.overlay');
  } else {
    window.addEventListener('load', function () { announceSlot('shell.overlay'); });
  }

  // 触摸区域随插件 DOM/动画变化重算（宠物走动、面板打开、菜单弹出都要更新）；
  // 500ms 轮询兜底 CSS 动画/transition 这类 MutationObserver 看不到的位置变化。
  try {
    new MutationObserver(scheduleRegion).observe(document.body, {
      childList: true, subtree: true,
      attributes: true, attributeFilter: ['style', 'class']
    });
  } catch (e) {}
  document.addEventListener('transitionend', scheduleRegion, true);
  window.addEventListener('resize', scheduleRegion);
  setInterval(reportTouchRegion, 500);

  log('client host ready plugin=' + pluginId + ' entry=' + entryPath);
})();
