(function () {
  var out = {
    bodyLength: document.body.innerHTML.length,
    status: (document.getElementById('ds-status') || {}).textContent || '',
    session: window.__clientSession ? 'present' : 'missing',
    children: []
  };
  var nodes = document.querySelectorAll('body *');
  for (var i = 0; i < nodes.length && out.children.length < 40; i += 1) {
    var el = nodes[i];
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    if (r.width < 5 || r.height < 5) continue;
    if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) continue;
    out.children.push({
      tag: el.tagName,
      id: el.id || '',
      cls: String(el.className || '').slice(0, 70),
      x: Math.round(r.x), y: Math.round(r.y),
      w: Math.round(r.width), h: Math.round(r.height),
      op: cs.opacity, z: cs.zIndex, pos: cs.position
    });
  }
  return JSON.stringify(out);
})()
