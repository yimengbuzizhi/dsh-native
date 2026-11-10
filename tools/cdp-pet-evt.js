(function () {
  var pet = null;
  var all = document.querySelectorAll("[data-dsh-pet]");
  for (var i = 0; i < all.length; i++) if (all[i].getAttribute("data-dsh-pet") === "root") pet = all[i];
  window.__petEvt = { start: 0, move: 0, end: 0, cancel: 0 };
  pet.addEventListener("touchstart", function () { window.__petEvt.start++ }, true);
  pet.addEventListener("touchmove", function () { window.__petEvt.move++ }, true);
  pet.addEventListener("touchend", function () { window.__petEvt.end++ }, true);
  pet.addEventListener("touchcancel", function () { window.__petEvt.cancel++ }, true);
  return "ok";
})()
