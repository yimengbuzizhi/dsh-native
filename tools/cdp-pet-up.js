(function () {
  window.dispatchEvent(new MouseEvent("mouseup", { bubbles: true, cancelable: true, view: window, clientX: 0, clientY: 0, button: 0, buttons: 0 }));
  return "up";
})()
