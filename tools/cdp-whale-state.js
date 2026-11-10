(function () {
  var container = document.getElementById('dsh-whale-floating-pet');
  var stage = document.querySelector('.dsh-whale-stage');
  var bubble = document.querySelector('.dsh-whale-bubble');
  var toolbar = document.querySelector('.dsh-whale-toolbar');
  var backdrop = document.querySelector('.dsh-whale-modal-backdrop');
  var rect = function (el) {
    if (!el) return null;
    var r = el.getBoundingClientRect();
    return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
  };
  var speechSupported = typeof window.speechSynthesis !== 'undefined' && window.speechSynthesis !== null;
  var voices = speechSupported && window.speechSynthesis.getVoices ? window.speechSynthesis.getVoices().length : -1;
  var audioCtx = !!(window.AudioContext || window.webkitAudioContext);
  var keys = [];
  try { for (var i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i)); } catch (e) {}
  var state = null;
  try { state = JSON.parse(window.__whaleStateJson ? window.__whaleStateJson() : 'null'); } catch (e) {}
  return JSON.stringify({
    title: document.title,
    ready: document.readyState,
    viewport: [window.innerWidth, window.innerHeight],
    dpr: window.devicePixelRatio,
    container: rect(container),
    stage: rect(stage),
    bubble: rect(bubble),
    toolbar: rect(toolbar),
    bubbleText: bubble ? bubble.textContent : null,
    badge: (document.querySelector('.dsh-whale-merit-badge') || {}).textContent || null,
    tokenBar: (document.querySelector('.dsh-whale-token-bar') || {}).textContent || null,
    modalOpen: !!(backdrop && backdrop.style.display === 'flex'),
    modalCard: rect(document.querySelector('.dsh-whale-modal-card')),
    toolbarButtons: Array.prototype.map.call(
      document.querySelectorAll('.dsh-whale-toolbar .dsh-whale-btn'),
      function (b) { return b.textContent.trim() }
    ),
    speechSynthesis: speechSupported,
    voices: voices,
    audioContext: audioCtx,
    bridge: typeof window.DSHNative === 'object' && !!window.DSHNative,
    storage: keys,
    merit: state && { today: state.todayMerit, total: state.totalMerit, speechSeq: state.speechSeq, acked: state.ackedSpeechSeq, current: state.currentSpeech && state.currentSpeech.text, queue: state.speechQueue }
  });
})()
