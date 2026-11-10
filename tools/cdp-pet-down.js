(function () {
  var pet = null;
  var all = document.querySelectorAll("[data-dsh-pet]");
  for (var i = 0; i < all.length; i++) if (all[i].getAttribute("data-dsh-pet") === "root") pet = all[i];
  if (!pet) return "no pet";
  var r = pet.getBoundingClientRect();
  var x = r.left + r.width / 2, y = r.top + r.height / 2;
  pet.dispatchEvent(new MouseEvent("mousedown", { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0, buttons: 1 }));
  return "down";
})()
