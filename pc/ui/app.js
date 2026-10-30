/* Phone-Cam PC UI. Plain JS, no build step. State comes from the Rust core over /api/events. */
(function () {
  'use strict';

  var REPO = 'Emre1001/Phone-Cam';
  var $ = function (id) { return document.getElementById(id); };
  var root = document.documentElement;
  var reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
  var t = function (k, p) { return I18n.t(k, p); };
  var state = null;

  /* ---------- Core API ---------- */
  function post(path, data) {
    var body = new URLSearchParams(data || {}).toString();
    return fetch(path, { method: 'POST', headers: { 'X-PC': '1', 'Content-Type': 'application/x-www-form-urlencoded' }, body: body });
  }

  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    Motion.enter(el, { from: 'bottom' });
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { Motion.exit(el, { to: 'bottom' }); }, 2200);
  }

  /* ---------- Theme (System / Light / Dark) with circular reveal ---------- */
  var mq = matchMedia('(prefers-color-scheme: dark)');
  function storedTheme() { try { return localStorage.getItem('theme') || 'system'; } catch (e) { return 'system'; } }

  function applyTheme(pref, origin) {
    var dark = pref === 'dark' || (pref === 'system' && mq.matches);
    var set = function () { root.dataset.theme = dark ? 'dark' : 'light'; root.dataset.themePref = pref; syncSegs(); };
    try { localStorage.setItem('theme', pref); } catch (e) { /* storage can be blocked */ }
    var unchanged = root.dataset.theme === (dark ? 'dark' : 'light');
    if (unchanged || reduceMotion.matches) { set(); return; }
    if (!document.startViewTransition) {
      root.classList.add('theme-fade'); set();
      setTimeout(function () { root.classList.remove('theme-fade'); }, 300);
      return;
    }
    root.classList.add('theme-switching');
    if (origin) root.classList.add('theme-reveal');
    var tr = document.startViewTransition(set);
    if (origin) {
      var x = origin.x, y = origin.y;
      var radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
      tr.ready.then(function () {
        root.animate(
          { clipPath: ['circle(0px at ' + x + 'px ' + y + 'px)', 'circle(' + radius + 'px at ' + x + 'px ' + y + 'px)'] },
          { duration: 700, easing: 'cubic-bezier(0.22, 1, 0.36, 1)', pseudoElement: '::view-transition-new(root)' });
      });
    }
    tr.finished.finally(function () { root.classList.remove('theme-switching', 'theme-reveal'); });
  }
  mq.addEventListener('change', function () { if (storedTheme() === 'system') applyTheme('system'); });
  function originOf(el) { var r = el.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  /* ---------- Segmented controls: thumb slides, spring ---------- */
  function placeThumb(seg, animate) {
    var thumb = seg.querySelector('.seg-thumb');
    var on = seg.querySelector('[aria-checked="true"]');
    if (!on || !seg.offsetWidth) return;
    var rtl = root.dir === 'rtl';
    var x = rtl ? on.offsetLeft - seg.clientWidth + on.offsetWidth : on.offsetLeft;
    var prev = parseFloat(thumb.dataset.x || 'NaN');
    thumb.style.width = on.offsetWidth + 'px';
    thumb.style.transform = 'translateX(' + x + 'px)';
    thumb.dataset.x = x;
    if (animate && !isNaN(prev) && prev !== x) Motion.toggle(thumb, x - prev, 0);
  }
  function setChecked(seg, attr, value, animate) {
    seg.querySelectorAll('[role="radio"]').forEach(function (b) { b.setAttribute('aria-checked', String(b.getAttribute(attr) === String(value))); });
    placeThumb(seg, animate);
  }
  function syncSegs() {
    setChecked($('theme-seg'), 'data-theme-pref', storedTheme(), false);
    if (state) setChecked($('rot-seg'), 'data-rot', state.rotation, false);
  }
  function radioKeys(seg) {
    seg.addEventListener('keydown', function (e) {
      var items = [].slice.call(seg.querySelectorAll('[role="radio"]'));
      var i = items.indexOf(document.activeElement);
      var step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
      if (i < 0 || !step) return;
      e.preventDefault();
      var next = items[(i + (root.dir === 'rtl' ? -step : step) + items.length) % items.length];
      next.focus(); next.click();
    });
  }

  /* ---------- Views and navigation ---------- */
  var views = { home: $('view-home'), settings: $('view-settings') };
  function showRoute(route) {
    Object.keys(views).forEach(function (k) { views[k].hidden = k !== route; });
    $('back').hidden = route === 'home';
    $('open-settings').hidden = route !== 'home';
    $('title').textContent = route === 'home' ? t('app.name') : t('settings.title');
    $('title').dataset.i18n = route === 'home' ? 'app.name' : 'settings.title';
    if (route === 'settings') { placeThumb($('theme-seg')); placeThumb($('rot-seg')); }
    $('page').parentElement.scrollTop = 0;
  }
  function onRoute(e) {
    var go = function () { showRoute(e.route); };
    if (!document.startViewTransition || reduceMotion.matches || e.direction === 'replace') { go(); return; }
    root.dataset.nav = e.direction === 'back' ? 'back' : 'forward';
    document.startViewTransition(go).finished.finally(function () { delete root.dataset.nav; });
  }

  /* ---------- Rendering ---------- */
  var lastKey = '';
  function fmtKbps(k) { return k >= 1000 ? I18n.number(k / 1000, { maximumFractionDigits: 1 }) + ' Mbit/s' : I18n.number(k) + ' kbit/s'; }

  function render() {
    if (!state) return;
    var s = state, sess = s.session;

    var pill = $('pill');
    pill.dataset.state = sess ? 'live' : (!s.ffmpeg ? 'warn' : 'idle');
    $('pill-text').textContent = sess ? t('status.live') : (!s.ffmpeg ? t('status.noffmpeg') : t('status.waiting'));

    $('idle').hidden = !!sess;
    $('live-tag').hidden = !sess;
    $('stats').hidden = !sess;
    $('stage-actions').hidden = !sess;
    var img = $('preview');
    if (sess && img.hidden) { img.src = '/preview.mjpeg?' + Date.now(); img.hidden = false; }
    if (!sess && !img.hidden) { img.hidden = true; img.removeAttribute('src'); }
    if (sess) {
      $('s-phone').textContent = sess.phone;
      $('s-via').textContent = sess.links.map(function (l) { return t(l.via === 'usb' ? 'via.usb' : 'via.wifi'); }).join(' + ') + ' · ' + t('via.encrypted');
      $('s-video').textContent = sess.outW + '×' + sess.outH + ' @ ' + sess.liveFps + ' · ' + sess.codec;
      $('s-rate').textContent = sess.links.length > 1
        ? sess.links.map(function (l) { return fmtKbps(l.kbps); }).join(' + ')
        : fmtKbps(sess.kbps);
      $('s-drop').textContent = I18n.number(sess.dropped);
    }

    renderUsb(s);
    $('ips').textContent = s.ips.length ? s.ips.map(function (ip) { return ip + ':' + s.camPort; }).join('\n') : t('wifi.noip');
    $('ips').style.whiteSpace = 'pre-line';
    $('pin').textContent = s.pin;
    $('fingerprint').textContent = s.fingerprint;
    $('pin-locked').hidden = !s.pinLocked;
    renderVcam(s);

    if (document.activeElement !== $('pc-name')) $('pc-name').value = s.name;
    $('mirror').setAttribute('aria-checked', String(s.mirror));
    setChecked($('rot-seg'), 'data-rot', s.rotation, true);
  }

  function renderUsb(s) {
    var key = JSON.stringify([s.usb, s.adb, root.lang]);
    if (key === lastKey) return;
    lastKey = key;
    var ul = $('usb-list'), note = $('usb-note');
    ul.textContent = '';
    note.hidden = true;
    if (!s.adb) { note.textContent = t('usb.noadb'); note.hidden = false; return; }
    if (!s.usb.length) { note.textContent = t('usb.none'); note.hidden = false; return; }
    s.usb.forEach(function (d) {
      var ok = d.state === 'device' && d.reversed;
      var li = document.createElement('li');
      li.className = 'device' + (ok ? ' ok' : '');
      var meta = document.createElement('div'); meta.className = 'meta';
      var name = document.createElement('div'); name.className = 'name'; name.textContent = d.model || d.serial;
      var sub = document.createElement('div'); sub.className = 'sub';
      sub.textContent = ok ? t('usb.ready') : d.state === 'unauthorized' ? t('usb.unauthorized') : t('usb.offline');
      meta.append(name, sub); li.append(meta);
      if (ok) {
        var b = document.createElement('button');
        b.className = 'btn small'; b.type = 'button'; b.textContent = t('usb.launch');
        b.addEventListener('click', function () { post('/api/usb/launch', { serial: d.serial }); toast(t('toast.launching')); });
        li.append(b);
      }
      ul.append(li);
    });
  }

  function renderVcam(s) {
    var text = $('vcam-text'), actions = $('vcam-actions'), err = $('vcam-error');
    actions.hidden = true;
    err.hidden = !s.vcam.error;
    if (s.vcam.error) err.textContent = t('vcam.failed', { error: s.vcam.error });
    if (!s.ffmpeg) text.textContent = t('vcam.noffmpeg');
    else if (s.os !== 'linux') text.textContent = t('vcam.windows');
    else if (s.vcam.device) text.textContent = t('vcam.ready', { device: s.vcam.device });
    else {
      text.textContent = s.vcam.busy ? t('vcam.busy') : t('vcam.missing');
      actions.hidden = false;
      $('vcam-setup').disabled = s.vcam.busy;
    }
  }

  /* ---------- Wiring ---------- */
  function init() {
    I18n.init();
    I18n.onChange(function () { lastKey = ''; render(); showRoute(currentRoute); syncSegs(); });
    initLanguagePicker($('lang-trigger'));
    document.querySelectorAll('[data-i18n-attr]').length; // attributes are applied by I18n.apply

    document.querySelectorAll('.enter').forEach(function (el, i) { el.style.setProperty('--i', i); });

    $('theme-seg').addEventListener('click', function (e) {
      var b = e.target.closest('[data-theme-pref]');
      if (!b) return;
      var prev = storedTheme();
      applyTheme(b.dataset.themePref, originOf(b));
      if (prev !== b.dataset.themePref) setChecked($('theme-seg'), 'data-theme-pref', b.dataset.themePref, true);
    });
    $('rot-seg').addEventListener('click', function (e) {
      var b = e.target.closest('[data-rot]');
      if (b) { post('/api/settings', { rotation: b.dataset.rot }); setChecked($('rot-seg'), 'data-rot', b.dataset.rot, true); }
    });
    radioKeys($('theme-seg')); radioKeys($('rot-seg'));
    $('mirror').addEventListener('click', function () {
      var on = $('mirror').getAttribute('aria-checked') !== 'true';
      $('mirror').setAttribute('aria-checked', String(on));
      post('/api/settings', { mirror: on ? '1' : '0' });
    });
    var nameTimer;
    $('pc-name').addEventListener('input', function () {
      clearTimeout(nameTimer);
      nameTimer = setTimeout(function () { post('/api/settings', { name: $('pc-name').value }); }, 400);
    });
    $('new-pin').addEventListener('click', function () { post('/api/pin/new'); });
    $('disconnect').addEventListener('click', function () { post('/api/disconnect'); });
    $('vcam-setup').addEventListener('click', function () { post('/api/vcam/setup', { persist: $('vcam-persist').checked ? '1' : '0' }); });
    $('quit').addEventListener('click', function () { post('/api/quit').finally(function () { window.close(); }); });

    $('open-settings').addEventListener('click', function () { BackStack.navigate('settings'); });
    $('back').addEventListener('click', function () { history.back(); });
    BackStack.onRoute(function (e) { currentRoute = e.route; onRoute(e); });
    BackStack.init({ root: 'home' });
    applyTheme(storedTheme());
    syncSegs();

    addEventListener('resize', function () { placeThumb($('theme-seg')); placeThumb($('rot-seg')); });
    connect();
  }
  var currentRoute = 'home';

  function connect() {
    fetch('/api/state').then(function (r) { return r.json(); }).then(function (s) { state = s; render(); startUpdates(); });
    var es = new EventSource('/api/events');
    es.onmessage = function (e) { state = JSON.parse(e.data); render(); };
    es.onerror = function () { /* the core quit or restarts; EventSource reconnects by itself */ };
  }

  /* ---------- Updates from GitHub Releases ---------- */
  var updateStarted = false;
  function startUpdates() {
    if (updateStarted || !state) return;
    updateStarted = true;
    var asset = null;
    var adapter = {
      mode: 'desktop',
      check: function () {
        return fetch('https://api.github.com/repos/' + REPO + '/releases/latest', { headers: { Accept: 'application/vnd.github+json' } })
          .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
          .then(function (rel) {
            var version = String(rel.tag_name || '').replace(/^v/, '');
            if (!newer(version, state.appVersion)) return null;
            var want = state.os === 'windows' ? 'phone-cam-pc-windows-x86_64.exe' : 'phone-cam-pc-linux-x86_64';
            asset = (rel.assets || []).filter(function (a) { return a.name === want; })[0] || null;
            if (!asset) return null;
            return { version: version, current: state.appVersion, notes: rel.body || '' };
          });
      },
      install: function (onProgress) {
        return new Promise(function (resolve, reject) {
          var es = new EventSource('/api/events');
          es.onmessage = function (e) {
            var s = JSON.parse(e.data);
            if (s.updateError) { es.close(); reject(new Error(s.updateError)); }
            else if (s.updatePct >= 0) { onProgress({ percent: s.updatePct }); if (s.updatePct >= 100) { es.close(); resolve(); } }
          };
          post('/api/update/install', { url: asset.browser_download_url, size: asset.size, digest: asset.digest || '' });
        });
      }
    };
    UpdateUI.init({ adapter: adapter, currentVersion: state.appVersion });
    UpdateUI.bindSettings();
  }
  function newer(remote, local) {
    var r = remote.split('.').map(Number), l = local.split('.').map(Number);
    for (var i = 0; i < Math.max(r.length, l.length); i++) {
      var d = (r[i] || 0) - (l[i] || 0);
      if (d) return d > 0;
    }
    return false;
  }

  init();
})();
