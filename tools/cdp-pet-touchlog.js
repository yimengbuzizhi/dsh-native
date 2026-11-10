(function () {
  window.__touchLog = [];
  window.__moveCalls = 0;
  window.__lastMove = null;
  function rec(type, e) {
    var t = e.touches && e.touches[0] ? e.touches[0] : (e.changedTouches ? e.changedTouches[0] : null);
    if (!t) return;
    window.__touchLog.push({ type: type, sx: t.screenX, sy: t.screenY, cx: t.clientX, cy: t.clientY, n: e.touches ? e.touches.length : -1 });
  }
  document.addEventListener("touchstart", function (e) { rec("start", e) }, true);
  document.addEventListener("touchmove", function (e) { rec("move", e) }, true);
  document.addEventListener("touchend", function (e) { rec("end", e) }, true);
  try {
    var orig = window.DSHNative.moveWindowBy.bind(window.DSHNative);
    window.DSHNative.moveWindowBy = function (dx, dy) {
      window.__moveCalls++;
      window.__lastMove = [dx, dy];
      return orig(dx, dy);
    };
  } catch (e) { window.__moveWrapError = String(e) }
  return "instrumented";
})()
