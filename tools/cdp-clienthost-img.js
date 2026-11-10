(function () {
  var out = {
    inner: [window.innerWidth, window.innerHeight, devicePixelRatio],
    bodyHtmlHead: document.body.innerHTML.slice(0, 1200),
    imgs: []
  };
  document.querySelectorAll('img').forEach(function (img) {
    var r = img.getBoundingClientRect();
    var chain = [];
    var el = img;
    for (var depth = 0; el && depth < 5; depth += 1) {
      var cs = getComputedStyle(el);
      chain.push({
        tag: el.tagName, cls: String(el.className || '').slice(0, 50),
        pos: cs.position, left: cs.left, right: cs.right, top: cs.top, bottom: cs.bottom,
        transform: cs.transform, rect: [Math.round(el.getBoundingClientRect().x), Math.round(el.getBoundingClientRect().y), Math.round(el.getBoundingClientRect().width), Math.round(el.getBoundingClientRect().height)]
      });
      el = el.parentElement;
    }
    out.imgs.push({
      src: (img.currentSrc || img.src || '').slice(-60),
      rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      complete: img.complete, natural: [img.naturalWidth, img.naturalHeight],
      chain: chain
    });
  });
  return JSON.stringify(out);
})()
