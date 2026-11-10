(function () {
  if (!window.__petOpenMenu) return "no bridge";
  window.__petOpenMenu();
  var menu = document.querySelector("[data-dsh-pet='menu']");
  if (!menu) return "menu missing";
  var items = menu.querySelectorAll("div");
  var labels = [];
  for (var i = 0; i < items.length; i++) labels.push(items[i].textContent);
  for (var j = 0; j < items.length; j++) {
    if (items[j].textContent.indexOf("隐藏") >= 0) { items[j].click(); return "clicked:" + labels.join("|"); }
  }
  return "no hide item:" + labels.join("|");
})()
