/* Site theme: System -> Hell -> Dunkel button with the circular reveal from the button.
   Same behaviour and CSS requirements as the app (see premium-frontend-design "Theme system"):
   the .theme-switching / .theme-reveal rules from site-base.css must be present.

   <button class="theme-btn" data-theme-cycle aria-label="Erscheinungsbild"> <svg>sun/moon/auto icons with [data-icon="system|light|dark"]</svg> </button>
   Put theme-boot (inline in <head>, before CSS) so the page never flashes:
     <script>(function(){var p='system';try{p=localStorage.getItem('theme')||'system'}catch(e){}var d=p==='dark'||(p==='system'&&matchMedia('(prefers-color-scheme: dark)').matches);var r=document.documentElement;r.dataset.theme=d?'dark':'light';r.dataset.themePref=p;
     var m=document.querySelector('meta[name=theme-color]');})()</script>
   Pass labels through SiteTheme.init({ labels: { system:'System', light:'Hell', dark:'Dunkel' }, onChange }) (use I18n.t). */
(function (global) {
  'use strict';
  var root = document.documentElement, mq = matchMedia('(prefers-color-scheme: dark)'), reduce = matchMedia('(prefers-reduced-motion: reduce)');
  var ORDER = ['system', 'light', 'dark'], opts = { labels: { system: 'System', light: 'Hell', dark: 'Dunkel' }, onChange: null, announce: null };

  function pref() { try { return localStorage.getItem('theme') || 'system'; } catch (e) { return 'system'; } }
  function isDark(p) { return p === 'dark' || (p === 'system' && mq.matches); }
  function syncMeta() {
    var dark = root.dataset.theme === 'dark';
    document.querySelectorAll('meta[name="theme-color"]:not([media])').forEach(function (m) { m.content = getComputedStyle(root).getPropertyValue('--bg').trim() || (dark ? '#131315' : '#f6f6f4'); });
  }
  function paint(p) {
    document.querySelectorAll('[data-theme-cycle]').forEach(function (b) {
      b.dataset.mode = p;
      b.setAttribute('aria-label', (opts.title || 'Theme') + ': ' + opts.labels[p]);
      b.title = opts.labels[p];
    });
    if (opts.onChange) opts.onChange(p);
  }
  function apply(p, origin) {
    var dark = isDark(p);
    var set = function () { root.dataset.theme = dark ? 'dark' : 'light'; root.dataset.themePref = p; syncMeta(); };
    try { localStorage.setItem('theme', p); } catch (e) { /* private mode */ }
    paint(p);
    if (root.dataset.theme === (dark ? 'dark' : 'light') || reduce.matches) { set(); return; }
    if (!document.startViewTransition) { root.classList.add('theme-fade'); set(); setTimeout(function () { root.classList.remove('theme-fade'); }, 300); return; }
    root.classList.add('theme-switching');
    if (origin) root.classList.add('theme-reveal');
    var tr = document.startViewTransition(set);
    if (origin) {
      var x = origin.x, y = origin.y, r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
      tr.ready.then(function () {
        root.animate({ clipPath: ['circle(0px at ' + x + 'px ' + y + 'px)', 'circle(' + r + 'px at ' + x + 'px ' + y + 'px)'] },
          { duration: 700, easing: 'cubic-bezier(0.22, 1, 0.36, 1)', pseudoElement: '::view-transition-new(root)' });
      });
    }
    tr.finished.finally(function () { root.classList.remove('theme-switching', 'theme-reveal'); });
  }
  function init(o) {
    opts = Object.assign(opts, o || {});
    paint(pref());
    syncMeta();
    document.querySelectorAll('[data-theme-cycle]').forEach(function (b) {
      b.addEventListener('click', function () {
        var next = ORDER[(ORDER.indexOf(pref()) + 1) % ORDER.length];
        var r = b.getBoundingClientRect();
        apply(next, { x: r.left + r.width / 2, y: r.top + r.height / 2 });
      });
    });
    mq.addEventListener('change', function () { if (pref() === 'system') apply('system'); });
  }
  global.SiteTheme = { init: init, apply: apply, pref: pref, relabel: function (labels, title) { opts.labels = labels; if (title) opts.title = title; paint(pref()); } };
})(window);
