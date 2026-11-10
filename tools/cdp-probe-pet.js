(function () {
  var p = null, all = document.querySelectorAll("[data-dsh-pet]");
  for (var i = 0; i < all.length; i++) { if (all[i].getAttribute("data-dsh-pet") === "root") { p = all[i]; break; } }
  if (!p) return JSON.stringify({ error: "no pet root", nodes: all.length });
  var r = p.getBoundingClientRect();
  var im = p.querySelector("img");
  var b = null;
  for (var j = 0; j < all.length; j++) { if (all[j].getAttribute("data-dsh-pet") === "bubble") { b = all[j]; break; } }
  var out = {
    title: document.title,
    iw: innerWidth,
    ih: innerHeight,
    dpr: devicePixelRatio,
    pet: {
      rect: [r.left, r.top, r.width, r.height],
      left: getComputedStyle(p).left,
      right: getComputedStyle(p).right,
      top: getComputedStyle(p).top,
      bottom: getComputedStyle(p).bottom,
      display: getComputedStyle(p).display,
      img: im ? {
        src: im.src,
        complete: im.complete,
        nw: im.naturalWidth,
        nh: im.naturalHeight,
        h: im.getBoundingClientRect().height
      } : null
    },
    bubble: b ? { text: b.textContent, display: getComputedStyle(b).display, rect: (function (x) { return [x.left, x.top, x.width, x.height] })(b.getBoundingClientRect()) } : null,
    bodyChildren: document.body.children.length
  };
  return JSON.stringify(out);
})()
