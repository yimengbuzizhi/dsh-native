(function () {
  var img = document.querySelector("img");
  var b = null;
  var all = document.querySelectorAll("[data-dsh-pet]");
  for (var i = 0; i < all.length; i++) {
    if (all[i].getAttribute("data-dsh-pet") === "bubble") { b = all[i]; break; }
  }
  return JSON.stringify({
    src: img ? img.src : null,
    natural: img ? [img.naturalWidth, img.naturalHeight] : null,
    bodyRect: img ? (function (r) { return [r.left, r.top, r.width, r.height] })(img.getBoundingClientRect()) : null,
    bubble: b ? { text: b.textContent, display: b.style.display } : null,
    menu: !!document.querySelector("[data-dsh-pet='menu']"),
    panel: !!document.querySelector("[data-dsh-pet='panel']"),
    picker: !!document.querySelector("[data-dsh-pet='picker']")
  });
})()
