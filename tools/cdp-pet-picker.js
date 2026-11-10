(function () {
  var menu = document.querySelector("[data-dsh-pet='menu']");
  if (!menu) return "menu missing";
  var items = menu.querySelectorAll("div");
  if (items.length === 0) return "no items";
  items[0].click();
  var picker = document.querySelector("[data-dsh-pet='picker']");
  if (!picker) return "picker missing";
  var r = picker.getBoundingClientRect();
  return JSON.stringify({ left: r.left, top: r.top, width: r.width, height: r.height, title: picker.textContent.slice(0, 40) });
})()
