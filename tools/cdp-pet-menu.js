(function () {
  if (!window.__petOpenMenu) return "no bridge";
  window.__petOpenMenu();
  var menu = document.querySelector("[data-dsh-pet='menu']");
  if (!menu) return "menu missing";
  var r = menu.getBoundingClientRect();
  return JSON.stringify({ left: r.left, top: r.top, width: r.width, height: r.height, items: Array.prototype.map.call(menu.querySelectorAll("div"), function (d) { return d.textContent; }) });
})()
