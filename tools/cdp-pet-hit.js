(function () {
  var e = document.elementFromPoint(211, 291);
  var all = document.querySelectorAll("[data-dsh-pet]");
  var list = [];
  for (var i = 0; i < all.length; i++) {
    var r = all[i].getBoundingClientRect();
    list.push(all[i].getAttribute("data-dsh-pet") + " " + all[i].tagName + " rect=" + [r.left, r.top, r.width, r.height].join(",") + " pe=" + getComputedStyle(all[i]).pointerEvents);
  }
  return JSON.stringify({ at: e ? e.tagName + "#" + e.id + "." + e.className : "none", atPet: e ? e.getAttribute("data-dsh-pet") : null, list: list });
})()
