new Promise(function (resolve) {
  var pet = null;
  var all = document.querySelectorAll("[data-dsh-pet]");
  for (var i = 0; i < all.length; i++) if (all[i].getAttribute("data-dsh-pet") === "root") pet = all[i];
  if (!pet) { resolve("no pet"); return; }
  var r = pet.getBoundingClientRect();
  var x = r.left + r.width / 2, y = r.top + r.height / 2;
  function fire(target, type, buttons) {
    target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0, buttons: buttons }));
  }
  fire(pet, "mousedown", 1);
  var samples = [];
  var t = setInterval(function () {
    samples.push(document.querySelector("img").src.split("/").slice(-2).join("/"));
  }, 250);
  setTimeout(function () {
    fire(window, "mouseup", 0);
    clearInterval(t);
    samples.push(document.querySelector("img").src.split("/").slice(-2).join("/"));
    resolve(JSON.stringify(samples));
  }, 1600);
})
