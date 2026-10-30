/* Scroll reveal and scroll-linked values. No library, one IntersectionObserver, transform/opacity only.

   <section data-reveal>                 fades and rises in once when 18% visible
   <li data-reveal style="--i:2">        stagger: delay = --i * 70ms (set --i via CSSOM or classes, not inline, when a strict CSP is used)
   <div data-reveal="mask">              text lines/blocks revealed from below a clip (headlines)
   Reveal.init({ rootMargin })           returns { destroy }
   Reveal.progress(el, fn)               calls fn(p) with p = 0..1 while el travels through the viewport (rAF-throttled, passive)

   Without JS or with prefers-reduced-motion everything is simply visible: the hidden state only exists under html.js-reveal,
   which this script adds, and never when reduced motion is requested. */
(function (global) {
  'use strict';
  var reduce = matchMedia('(prefers-reduced-motion: reduce)');

  function init(o) {
    o = o || {};
    if (reduce.matches || !('IntersectionObserver' in global)) return { destroy: function () {} };
    document.documentElement.classList.add('js-reveal');
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        e.target.classList.add('is-in'); io.unobserve(e.target);
      });
    }, { threshold: o.threshold || 0.18, rootMargin: o.rootMargin || '0px 0px -6% 0px' });
    document.querySelectorAll('[data-reveal]').forEach(function (el) { io.observe(el); });
    return { destroy: function () { io.disconnect(); } };
  }

  function progress(el, fn) {
    var ticking = false;
    function calc() {
      ticking = false;
      var r = el.getBoundingClientRect(), vh = innerHeight || 1;
      fn(Math.min(1, Math.max(0, (vh - r.top) / (vh + r.height))));
    }
    function on() { if (!ticking) { ticking = true; requestAnimationFrame(calc); } }
    addEventListener('scroll', on, { passive: true }); addEventListener('resize', on, { passive: true });
    calc();
    return function () { removeEventListener('scroll', on); removeEventListener('resize', on); };
  }

  global.Reveal = { init: init, progress: progress };
})(window);
