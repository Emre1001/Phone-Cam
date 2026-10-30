/* DownloadKit: turns a GitHub repository's latest release into honest, always-working download buttons.

   - Detects the visitor's OS (and Apple Silicon vs Intel where the browser can tell) and highlights the matching file.
   - Reads https://api.github.com/repos/<repo>/releases/latest (no token; cached for 10 minutes in sessionStorage,
     because unauthenticated calls are limited to 60 per hour per IP).
   - NEVER breaks: if the API fails, is rate limited, or the repo has no release yet, every button falls back to
     https://github.com/<repo>/releases/latest (or the repo page) with a neutral label. A download button that does
     nothing is the worst bug a landing page can have.
   - Shows only real data: version, date, file size, SHA-256 (only when GitHub provides the asset digest). No fake counters.
   - Links are only accepted when they point to https://github.com/<repo>/releases/download/...

   Markup contract (all optional, use what the page needs):
     [data-dl-primary]                         <a> main button; href + label are filled
       [data-dl-label]                           text node holder inside it ("Für Windows laden")
       [data-dl-meta]                            small text inside it ("v1.4.2 · 84 MB")
     [data-dl-platform="windows|macos|linux|android"]   <a> per platform; gets href; [data-dl-size] [data-dl-hash]
                                                          children are filled; class is-current for the visitor's OS,
                                                          attribute data-state="ready|missing"
     [data-dl-variants]                        container; gets one <a> per file when the platform has several (dmg arm64/x64, deb/rpm/AppImage)
     [data-dl-version] [data-dl-date] [data-dl-notes]   text / <a href=release page>
     [data-dl-state]                           element that receives data-state="loading|ready|fallback|none" (for CSS)
   Strings: pass `t(key, params)` (e.g. I18n.t) or accept the German/English defaults below.

     DownloadKit.init({ repo: 'owner/name', platforms: {...optional overrides}, t: I18n.t, lang: 'de' });
     DownloadKit.onChange(model => ...)        called with { state, release, os, files, primary }
*/
(function (global) {
  'use strict';

  var DEFAULT_PLATFORMS = {
    windows: { name: 'Windows', match: [/\.exe$/i, /\.msi$/i, /\.msix$/i], prefer: [/setup|installer/i, /x64|amd64/i], avoid: [/arm64/i, /blockmap/i] },
    macos:   { name: 'macOS',   match: [/\.dmg$/i, /\.pkg$/i], prefer: [/\.dmg$/i], avoid: [/blockmap/i],
               variants: [{ key: 'arm64', re: /arm64|aarch64|apple.?silicon/i }, { key: 'x64', re: /x64|x86_64|intel|amd64/i }] },
    linux:   { name: 'Linux',   match: [/\.appimage$/i, /\.deb$/i, /\.rpm$/i, /\.tar\.(gz|xz)$/i], prefer: [/\.appimage$/i, /x86_64|amd64|x64/i], avoid: [/arm64|aarch64/i, /blockmap/i] },
    android: { name: 'Android', match: [/\.apk$/i], prefer: [/universal|release/i], avoid: [/debug|unsigned/i] }
  };

  var STRINGS = {
    de: {
      loading: 'Lade …', for: 'Für {os} laden', all: 'Alle Downloads', soon: 'Bald verfügbar', none: 'Noch kein Release',
      apple: 'Apple Silicon', intel: 'Intel', size: '{size}', version: 'Version {version}', notes: 'Release-Notizen',
      ios: 'Für iPhone/iPad gibt es noch keine App', mobile: 'Öffne diese Seite am Desktop für Windows, macOS und Linux'
    },
    en: {
      loading: 'Loading …', for: 'Download for {os}', all: 'All downloads', soon: 'Coming soon', none: 'No release yet',
      apple: 'Apple Silicon', intel: 'Intel', size: '{size}', version: 'Version {version}', notes: 'Release notes',
      ios: 'There is no iPhone/iPad app yet', mobile: 'Open this page on a desktop for Windows, macOS and Linux'
    }
  };

  var cfg = null, model = null, listeners = [];

  /* ---------- helpers (pure, exported for tests) ---------- */
  function fmt(s, p) { return String(s).replace(/\{(\w+)\}/g, function (_, k) { return p && p[k] != null ? p[k] : ''; }); }
  function formatSize(bytes, locale) {
    if (!bytes && bytes !== 0) return '';
    var mb = bytes / 1048576;
    var n = new Intl.NumberFormat(locale || undefined, { maximumFractionDigits: mb >= 100 ? 0 : 1 });
    return mb >= 1 ? n.format(mb) + ' MB' : n.format(bytes / 1024) + ' KB';
  }
  function detect(nav) {
    nav = nav || global.navigator || {};
    var ua = nav.userAgent || '', plat = (nav.userAgentData && nav.userAgentData.platform) || nav.platform || '';
    var touch = (nav.maxTouchPoints || 0) > 1;
    var os = 'other';
    if (/android/i.test(ua)) os = 'android';
    else if (/iphone|ipad|ipod/i.test(ua) || (/mac/i.test(plat) && touch)) os = 'ios';        // iPadOS reports "MacIntel" with touch
    else if (/win/i.test(plat) || /windows/i.test(ua)) os = 'windows';
    else if (/mac/i.test(plat) || /macintosh|mac os x/i.test(ua)) os = 'macos';
    else if (/linux|x11|cros/i.test(plat + ' ' + ua)) os = 'linux';
    return { os: os, arch: 'unknown' };
  }
  function resolveArch(info) {                       // Chrome/Edge can tell; Safari and Firefox cannot (they report Intel)
    var uad = global.navigator && global.navigator.userAgentData;
    if (!uad || !uad.getHighEntropyValues) return Promise.resolve(info);
    return uad.getHighEntropyValues(['architecture', 'bitness']).then(function (v) {
      info.arch = /arm/i.test(v.architecture || '') ? 'arm64' : v.architecture ? 'x64' : 'unknown';
      return info;
    }).catch(function () { return info; });
  }
  function scoreAsset(a, spec) {
    var n = a.name, s = 0;
    (spec.prefer || []).forEach(function (re, i) { if (re.test(n)) s += 10 - i; });
    (spec.avoid || []).forEach(function (re) { if (re.test(n)) s -= 20; });
    return s;
  }
  function pickFiles(assets, platforms, repo) {
    var prefix = 'https://github.com/' + repo + '/releases/download/';
    var out = {};
    Object.keys(platforms).forEach(function (key) {
      var spec = platforms[key];
      var list = (assets || []).filter(function (a) {
        return a && typeof a.name === 'string' && typeof a.browser_download_url === 'string'
          && a.browser_download_url.indexOf(prefix) === 0 && spec.match.some(function (re) { return re.test(a.name); })
          && !/\.(blockmap|sig|asc|sha256|sha512|yml|yaml|json)$/i.test(a.name);
      }).map(function (a) {
        return { name: a.name, url: a.browser_download_url, size: a.size, hash: /^sha256:([0-9a-f]{64})$/i.test(a.digest || '') ? RegExp.$1.toLowerCase() : '', score: scoreAsset(a, spec) };
      }).sort(function (x, y) { return y.score - x.score || x.name.localeCompare(y.name); });
      if (spec.variants) list.forEach(function (f) { var v = spec.variants.filter(function (x) { return x.re.test(f.name); })[0]; f.variant = v ? v.key : ''; });
      out[key] = list;
    });
    return out;
  }
  function best(files, os, arch) {
    var list = files[os] || [];
    if (!list.length) return null;
    if (os === 'macos' && arch !== 'unknown') {
      var hit = list.filter(function (f) { return f.variant === arch; })[0];
      if (hit) return hit;
    }
    if (os === 'macos') {                            // Safari cannot tell: prefer a universal build, else Apple Silicon (all Macs since 2020)
      var universal = list.filter(function (f) { return /universal/i.test(f.name) || !f.variant; })[0];
      if (universal) return universal;
      var arm = list.filter(function (f) { return f.variant === 'arm64'; })[0];
      if (arm) return arm;
    }
    return list[0];
  }

  /* ---------- data ---------- */
  function load() {
    var key = 'dl:' + cfg.repo, now = Date.now();
    try {
      var c = JSON.parse(sessionStorage.getItem(key) || 'null');
      if (c && now - c.t < cfg.cacheMinutes * 60000) return Promise.resolve(c.data);
    } catch (e) { /* storage blocked */ }
    var ctl = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctl) ctl.abort(); }, cfg.timeoutMs);
    return fetch('https://api.github.com/repos/' + cfg.repo + '/releases/latest', {
      headers: { Accept: 'application/vnd.github+json' }, signal: ctl ? ctl.signal : undefined
    }).then(function (r) {
      clearTimeout(timer);
      if (r.status === 404) return { none: true };
      if (!r.ok) throw new Error('GitHub API ' + r.status);
      return r.json();
    }).then(function (data) {
      try { sessionStorage.setItem(key, JSON.stringify({ t: now, data: data })); } catch (e) { /* ignore */ }
      return data;
    }).catch(function (err) { clearTimeout(timer); throw err; });
  }

  /* ---------- UI ---------- */
  function tr(key, p) {
    if (cfg.t) { var v = cfg.t('download.' + key, p); if (v && v !== 'download.' + key) return v; }
    var l = STRINGS[cfg.lang] || STRINGS[(cfg.lang || '').slice(0, 2)] || STRINGS.en;
    return fmt(l[key] != null ? l[key] : STRINGS.en[key] || key, p);
  }
  function $all(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
  function setText(el, text) { if (el) el.textContent = text; }

  function render() {
    var m = model, repoUrl = 'https://github.com/' + cfg.repo, latest = repoUrl + '/releases/latest';
    $all('[data-dl-state]').forEach(function (el) { el.dataset.state = m.state; });
    var locale = cfg.lang || document.documentElement.lang || undefined;

    var rel = m.release;
    $all('[data-dl-version]').forEach(function (el) { setText(el, rel ? String(rel.tag_name || '').replace(/^v/i, 'v') : ''); });
    $all('[data-dl-date]').forEach(function (el) {
      if (!rel || !rel.published_at) { setText(el, ''); return; }
      el.setAttribute('datetime', rel.published_at);
      setText(el, new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(rel.published_at)));
    });
    $all('[data-dl-notes]').forEach(function (el) { el.href = rel && rel.html_url ? rel.html_url : latest; setText(el, tr('notes')); });

    $all('[data-dl-platform]').forEach(function (el) {
      var key = el.getAttribute('data-dl-platform'), list = (m.files && m.files[key]) || [];
      var f = key === m.os ? m.primary : best(m.files || {}, key, 'unknown');
      el.classList.toggle('is-current', key === m.os);
      if (m.state === 'ready' && f) {
        el.href = f.url; el.removeAttribute('aria-disabled'); el.dataset.state = 'ready';
        el.querySelectorAll('[data-dl-size]').forEach(function (s) { setText(s, formatSize(f.size, locale)); });
        el.querySelectorAll('[data-dl-hash]').forEach(function (s) { if (f.hash) { s.hidden = false; setText(s, 'SHA-256 ' + f.hash.slice(0, 12) + '…'); s.title = f.hash; } else s.hidden = true; });
      } else if (m.state === 'ready') {
        el.removeAttribute('href'); el.setAttribute('aria-disabled', 'true'); el.dataset.state = 'missing';
        el.querySelectorAll('[data-dl-size]').forEach(function (s) { setText(s, tr('soon')); });
        el.querySelectorAll('[data-dl-hash]').forEach(function (s) { s.hidden = true; });
      } else {
        el.href = m.state === 'none' ? repoUrl : latest; el.removeAttribute('aria-disabled'); el.dataset.state = 'fallback';
        el.querySelectorAll('[data-dl-size]').forEach(function (s) { setText(s, ''); });
        el.querySelectorAll('[data-dl-hash]').forEach(function (s) { s.hidden = true; });
      }
      if (list.length > 1) el.dataset.files = String(list.length);
    });

    $all('[data-dl-variants]').forEach(function (box) {
      box.textContent = '';
      var list = (m.files && m.files[m.os]) || [];
      if (m.state !== 'ready' || list.length < 2) { box.hidden = true; return; }
      box.hidden = false;
      list.slice(0, 5).forEach(function (f) {
        var a = document.createElement('a');
        a.href = f.url; a.rel = 'noopener';
        var label = f.variant === 'arm64' ? tr('apple') : f.variant === 'x64' ? tr('intel') : (/\.appimage$/i.test(f.name) ? 'AppImage' : f.name.replace(/^.*\./, '.').toLowerCase());
        a.textContent = label + ' · ' + formatSize(f.size, locale);
        if (m.primary && f.url === m.primary.url) a.setAttribute('aria-current', 'true');
        box.appendChild(a);
      });
    });

    $all('[data-dl-primary]').forEach(function (a) {
      var label = a.querySelector('[data-dl-label]') || a, meta = a.querySelector('[data-dl-meta]');
      var p = m.primary;
      if (m.state === 'loading') { setText(label, tr('loading')); a.href = latest; setText(meta, ''); return; }
      if (m.state === 'ready' && p) {
        a.href = p.url;
        setText(label, tr('for', { os: cfg.platforms[m.os].name }));
        setText(meta, [rel && rel.tag_name, formatSize(p.size, locale)].filter(Boolean).join(' · '));
      } else if (m.state === 'ready') {                              // release exists, but not for this OS
        a.href = rel && rel.html_url ? rel.html_url : latest;
        setText(label, tr('all')); setText(meta, rel ? rel.tag_name : '');
      } else if (m.state === 'none') {
        a.href = repoUrl; setText(label, tr('none')); setText(meta, '');
      } else {
        a.href = latest; setText(label, tr('all')); setText(meta, '');
      }
    });
    $all('[data-dl-hint]').forEach(function (el) {
      el.textContent = m.os === 'ios' ? tr('ios') : (m.os === 'other' || m.os === 'android') && m.state === 'ready' && !m.primary ? tr('mobile') : '';
    });
    listeners.forEach(function (fn) { try { fn(m); } catch (e) { console.error(e); } });
  }

  function init(options) {
    cfg = Object.assign({ repo: '', platforms: {}, t: null, lang: (document.documentElement.lang || 'en').slice(0, 2), cacheMinutes: 10, timeoutMs: 6000 }, options || {});
    if (!/^[\w.-]+\/[\w.-]+$/.test(cfg.repo)) { console.error('[DownloadKit] repo must look like "owner/name"'); return Promise.resolve(null); }
    var merged = {};
    Object.keys(DEFAULT_PLATFORMS).forEach(function (k) { merged[k] = Object.assign({}, DEFAULT_PLATFORMS[k], (options && options.platforms && options.platforms[k]) || {}); });
    cfg.platforms = merged;

    var info = detect();
    model = { state: 'loading', release: null, os: info.os, arch: info.arch, files: null, primary: null };
    render();
    return resolveArch(info).then(function (i) { model.arch = i.arch; return load(); }).then(function (data) {
      if (data && data.none) { model.state = 'none'; }
      else {
        model.release = data;
        model.files = pickFiles(data.assets, cfg.platforms, cfg.repo);
        model.primary = best(model.files, model.os, model.arch);
        model.state = 'ready';
      }
      render(); return model;
    }).catch(function (err) {
      console.warn('[DownloadKit] falling back to the releases page:', err && err.message);
      model.state = 'fallback'; render(); return model;
    });
  }

  global.DownloadKit = {
    init: init,
    refresh: function () { if (model) render(); },
    onChange: function (fn) { listeners.push(fn); },
    _test: { detect: detect, pickFiles: pickFiles, best: best, formatSize: formatSize, DEFAULT_PLATFORMS: DEFAULT_PLATFORMS }
  };
})(window);
