(function () {
  var log = window.__touchLog || [];
  var moves = log.filter(function (x) { return x.type === "move" });
  return JSON.stringify({
    calls: window.__moveCalls,
    lastMove: window.__lastMove,
    wrapError: window.__moveWrapError || null,
    total: log.length,
    first: log.slice(0, 3),
    lastMoves: moves.slice(-3)
  });
})()
