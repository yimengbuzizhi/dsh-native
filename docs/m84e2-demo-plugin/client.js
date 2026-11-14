(function () {
  // M84-E2：插件自己的浏览器半侧。按宿主约定注册 __ModuleLoader__ entry，
  // 宿主负责加载 → 调用 apply(ctx)；插件不按插件 id 画原生窗口。
  function applyClient(ctx) {
    var root = document.getElementById('ds-root');
    if (!root) return;
    root.innerHTML =
      '<div style="margin:16px;padding:14px 16px;border-radius:14px;' +
      'background:#0f172a;color:#e2e8f0;font:14px/1.5 -apple-system,sans-serif;' +
      'box-shadow:0 8px 24px rgba(0,0,0,.35)">' +
      '<div style="font-size:12px;letter-spacing:.08em;color:#7dd3fc">M84-E2 · dsh.client</div>' +
      '<div style="font-weight:600;margin-top:4px">这是 m84e2-demo 自己的页面</div>' +
      '<div style="font-size:12px;color:#94a3b8;margin-top:4px">route /m84e2 · 宿主不重画</div>' +
      '</div>';
    try {
      window.__DSH_HOST__.setContentSize(300, 140);
    } catch (e) {}
  }

  window.__ModuleLoader__.load({
    id: 'm84e2-demo',
    factory: function () {
      return { apply: applyClient };
    }
  });
})();
