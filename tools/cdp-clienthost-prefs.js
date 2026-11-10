(function () {
  var out = { keys: [], prefs: null };
  for (var i = 0; i < localStorage.length; i += 1) out.keys.push(localStorage.key(i));
  try { out.prefs = JSON.parse(localStorage.getItem('dsh-client-ui-pet-prefs') || 'null'); } catch (e) { out.prefs = 'parse-error'; }
  if (!out.prefs) {
    for (var j = 0; j < localStorage.length; j += 1) {
      var k = localStorage.key(j);
      if (k && k.indexOf('pet') >= 0) { try { out.prefs = JSON.parse(localStorage.getItem(k)); out.prefsKey = k; } catch (e2) {} }
    }
  }
  return JSON.stringify(out);
})()
