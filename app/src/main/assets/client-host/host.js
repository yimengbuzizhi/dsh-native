(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var pluginId = params.get('plugin') || '';
  var entryPath = params.get('entry') || 'lib/client.js';
  var pluginName = params.get('name') || pluginId;
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
    setPassThrough: function () {}
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
  var sessions = {
    list: {
      getSnapshot: function () { return { current: 'dsh-native', byId: {} }; },
      subscribe: function () { return function () {}; }
    },
    binding: function () { return { session: sessionFace }; },
    scope: function () { return sessionFace; },
    active: function () { return 'dsh-native'; }
  };

  window.__clientSession = function (json) {
    try { snapshot = JSON.parse(json); } catch (e) { return; }
    listeners.slice().forEach(function (fn) {
      try { fn(); } catch (e) { log('session listener error: ' + (e && e.message || e)); }
    });
    sessionHandlers.slice().forEach(function (fn) {
      try { fn(snapshot); } catch (e) { log('host session handler error: ' + (e && e.message || e)); }
    });
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
      default:
        throw new Error('宿主未提供模块：' + name);
    }
  }

  // ---- slots 最小实现（shell.overlay 渲染成整页层；settings.* 先不渲染） ----
  var slotRegistrations = [];
  var slotInjects = {};
  var slotRoots = {};

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
          slotRoots[reg.key] = window.ReactDOM.createRoot(mount);
        }
        try {
          slotRoots[reg.key].render(window.React.createElement(reg.component, {}));
        } catch (e) {
          log('slot render failed: ' + (e && e.message || e));
        }
      });
    Object.keys(slotRoots).forEach(function (key) {
      if (seen[key]) return;
      try { slotRoots[key].unmount(); } catch (e) {}
      delete slotRoots[key];
    });
  }

  var slots = {
    snapshot: function () {
      return slotRegistrations.map(function (reg) {
        return { name: reg.name, id: reg.id, order: reg.order || 0 };
      });
    },
    register: function (spec, component) {
      if (!spec || !spec.name || typeof component !== 'function') {
        throw new Error('slots.register 需要 {name} 与组件');
      }
      var reg = {
        key: String(spec.name) + ':' + String(spec.id || slotRegistrations.length),
        name: String(spec.name),
        id: String(spec.id || ''),
        order: Number(spec.order || 0),
        component: component
      };
      slotRegistrations.push(reg);
      renderSlots();
      var pending = slotInjects[reg.name];
      if (pending && pending.length) {
        delete slotInjects[reg.name];
        pending.forEach(function (fn) { try { fn(); } catch (e) { log('slot inject failed: ' + (e && e.message || e)); } });
      }
      return function () {
        var index = slotRegistrations.indexOf(reg);
        if (index >= 0) slotRegistrations.splice(index, 1);
        renderSlots();
      };
    },
    inject: function (name, fn) {
      var exists = slotRegistrations.some(function (reg) { return reg.name === name; });
      if (exists) {
        try { return fn() || function () {}; } catch (e) {
          log('slot inject failed: ' + (e && e.message || e));
          return function () {};
        }
      }
      (slotInjects[name] = slotInjects[name] || []).push(fn);
      return function () {
        var list = slotInjects[name];
        if (!list) return;
        var index = list.indexOf(fn);
        if (index >= 0) list.splice(index, 1);
      };
    }
  };

  // ---- ctx：DSH client runtime 的最小面 ----
  var disposers = [];
  var ctx = {
    get: function (name) { return name === 'sessions' ? sessions : undefined; },
    effect: function (fn) {
      try {
        var dispose = fn();
        if (typeof dispose === 'function') disposers.push(dispose);
        return dispose || function () {};
      } catch (e) {
        log('effect error: ' + (e && e.message || e));
        return function () {};
      }
    },
    on: function () { return function () {}; },
    slots: slots,
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
