/* BYME.AM app: выполняется на каждой странице сайта внутри приложения. */
(function () {
  if (window.__bymeInjected) return;
  window.__bymeInjected = true;
  document.documentElement.classList.add('byme-app');

  // В приложении не показываем «Скачать приложение»
  try {
    var st = document.createElement('style');
    st.textContent = '#ap,.ap-open,[data-app-dl],a[href="/app"],a[href$="/app"]{display:none!important}';
    (document.head || document.documentElement).appendChild(st);
  } catch (e) { }

  // ---- голосовой поиск: Web Speech API поверх Android SpeechRecognizer
  if (!window.SpeechRecognition && !window.webkitSpeechRecognition && window.BYMEVoice && BYMEVoice.available()) {
    var Rec = function () { this.lang = 'ru-RU'; this.interimResults = false; this.continuous = false; this.maxAlternatives = 1; this.onresult = null; this.onend = null; this.onerror = null; this.onstart = null; };
    Rec.prototype.start = function () { window.__bymeRec = this; BYMEVoice.start(this.lang || 'ru-RU'); };
    Rec.prototype.stop = function () { BYMEVoice.stop(); };
    Rec.prototype.abort = function () { BYMEVoice.stop(); };
    Rec.prototype.addEventListener = function (t, fn) { this['on' + t] = fn; };
    window.__bymeVoice = function (type, text, isFinal) {
      var r = window.__bymeRec; if (!r) return;
      if (type === 'start') { if (r.onstart) r.onstart({}); return; }
      if (type === 'result') {
        if (!isFinal && !r.interimResults) return;
        var res = [{ transcript: text, confidence: 0.9 }]; res.isFinal = !!isFinal;
        if (r.onresult) r.onresult({ results: [res], resultIndex: 0 });
        return;
      }
      if (type === 'error') { if (r.onerror) r.onerror({ error: text }); return; }
      if (type === 'end') { window.__bymeRec = null; if (r.onend) r.onend({}); }
    };
    window.SpeechRecognition = Rec;
    window.webkitSpeechRecognition = Rec;
  }

  // ---- скачивание blob:-файлов (счета PDF, выгрузки) → «Загрузки» телефона
  window.__bymeSaveBlob = function (url, name) {
    try {
      fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
        var fr = new FileReader();
        fr.onload = function () { var s = String(fr.result); if (window.BYMEFiles) BYMEFiles.save(name || 'file', b.type || 'application/octet-stream', s.slice(s.indexOf(',') + 1)); };
        fr.readAsDataURL(b);
      });
    } catch (e) { }
  };
  var open0 = window.open;
  window.open = function (u) {
    if (u && String(u).indexOf('blob:') === 0) { window.__bymeSaveBlob(String(u), ''); return null; }
    return open0.apply(window, arguments);
  };
  var click0 = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.href && this.href.indexOf('blob:') === 0) { window.__bymeSaveBlob(this.href, this.getAttribute('download') || ''); return; }
    return click0.call(this);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a && a.href.indexOf('blob:') === 0) { e.preventDefault(); window.__bymeSaveBlob(a.href, a.getAttribute('download') || ''); }
  }, true);
})();
