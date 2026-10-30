/* Motion: the choreography rules as small WAAPI helpers (no library).
     Motion.enter(el, { from: 'bottom'|'top'|'start'|'end'|'none', distance: 16, delay: 0 })   ease-out, blur along the motion axis
     Motion.exit(el, { to: 'bottom'|..., remove: false })        ease-in, shorter, resolves when finished (then hides/removes)
     Motion.spring(el, { from: 'bottom'|..., distance: 24 })     INTERFACE motion: pull back, then spring with slight overshoot
     Motion.toggle(el, dx, dy)                                    spring move of an indicator/thumb to a new offset (with pull-back)
     Motion.press(el)                                             pressed feedback: quick dip, springy return
     Motion.stagger(els, fn, { frames: 3 })                       calls fn(el, i) offset by three frames (50ms)
   Directional blur: SVG blur with stdDeviation on ONE axis only (horizontal motion blurs x, vertical motion blurs y), applied
   only while the element is in flight, then removed (a permanent filter would rasterize the element and blur text).
   More than 12 simultaneous blurred elements fall back to opacity only (filters are not free).
   prefers-reduced-motion: no movement or blur, a quick fade only. Every promise resolves, also when animations are cancelled. */
(function (global) {
  'use strict';
  var EASE_OUT = 'cubic-bezier(0.16, 1, 0.3, 1)', EASE_IN = 'cubic-bezier(0.7, 0, 0.84, 0)';
  var SPRING = getComputedStyle(document.documentElement).getPropertyValue('--spring').trim() || 'cubic-bezier(0.34, 1.35, 0.64, 1)';
  var FRAME = 1000 / 60, reduce = matchMedia('(prefers-reduced-motion: reduce)'), live = 0, uid = 0, svg = null;

  function dir(name, d) {            // direction the element starts from -> [x, y] offset in px; logical start/end follow dir=rtl
    var rtl = document.documentElement.dir === 'rtl', s = rtl ? 1 : -1;
    return { bottom: [0, d], top: [0, -d], start: [s * d, 0], end: [-s * d, 0], none: [0, 0] }[name || 'bottom'] || [0, d];
  }
  function ensureSvg() {
    if (svg) return svg;
    svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('aria-hidden', 'true'); svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
    svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';        // CSSOM: allowed under a strict CSP
    document.body.appendChild(svg); return svg;
  }
  /* blur along x or y for `ms`, starting at `px` and decaying to 0 (or growing, when reverse) */
  function blurAlong(el, axis, px, ms, reverse) {
    if (!px || live >= 12 || reduce.matches) return;
    var host = ensureSvg(), id = 'mb' + (++uid), NS = 'http://www.w3.org/2000/svg';
    var f = document.createElementNS(NS, 'filter'), g = document.createElementNS(NS, 'feGaussianBlur');
    f.setAttribute('id', id); f.setAttribute('x', '-20%'); f.setAttribute('y', '-20%'); f.setAttribute('width', '140%'); f.setAttribute('height', '140%');
    g.setAttribute('stdDeviation', axis === 'x' ? px + ' 0' : '0 ' + px);
    f.appendChild(g); host.appendChild(f);
    el.style.filter = 'url(#' + id + ')'; live++;
    var t0 = performance.now(), done = false;
    (function tick(now) {
      if (done) return;
      var t = Math.min(1, (now - t0) / ms), k = reverse ? t : 1 - t, v = px * k * k;         // quadratic decay: blur is strongest at peak speed
      g.setAttribute('stdDeviation', axis === 'x' ? v.toFixed(2) + ' 0' : '0 ' + v.toFixed(2));
      if (t < 1) requestAnimationFrame(tick); else end();
    })(t0);
    function end() { if (done) return; done = true; if (!reverse) el.style.filter = ''; f.remove(); live--; }
    setTimeout(end, ms + 80);
    return end;
  }
  function run(el, keyframes, opts) {
    var a = el.animate(keyframes, Object.assign({ fill: 'both' }, opts));
    return a.finished.then(function () { try { a.cancel(); } catch (e) {} }, function () {});
  }
  function fade(el, show) {
    return run(el, [{ opacity: show ? 0 : 1 }, { opacity: show ? 1 : 0 }], { duration: 120, easing: 'linear' });
  }

  function enter(el, o) {
    o = o || {}; if (reduce.matches) return fade(el, true);
    var d = dir(o.from, o.distance == null ? 16 : o.distance), ms = o.duration || 420, axis = d[0] ? 'x' : 'y';
    el.hidden = false;
    if (d[0] || d[1]) blurAlong(el, axis, o.blur == null ? 5 : o.blur, ms * 0.75);
    return run(el, [{ opacity: 0, transform: 'translate(' + d[0] + 'px,' + d[1] + 'px)' }, { opacity: 1, transform: 'none' }],
      { duration: ms, delay: o.delay || 0, easing: EASE_OUT });                              // entrance: ease-out
  }
  function exit(el, o) {
    o = o || {}; var p;
    if (reduce.matches) p = fade(el, false);
    else {
      var d = dir(o.to, o.distance == null ? 10 : o.distance), ms = o.duration || 220, axis = d[0] ? 'x' : 'y';
      if (d[0] || d[1]) blurAlong(el, axis, o.blur == null ? 4 : o.blur, ms, true);
      p = run(el, [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'translate(' + d[0] + 'px,' + d[1] + 'px)' }],
        { duration: ms, easing: EASE_IN });                                                  // exit: ease-in, shorter than the entrance
    }
    return p.then(function () { if (el.style.filter) el.style.filter = ''; if (o.remove) el.remove(); else el.hidden = true; });
  }
  /* interface motion: anticipation (pulls back a few px AND slightly the opposite way), then the spring with ~5% overshoot */
  function spring(el, o) {
    o = o || {}; if (reduce.matches) return fade(el, true);
    var d = dir(o.from, o.distance == null ? 24 : o.distance), ms = o.duration || 620, pull = o.pull == null ? 6 : o.pull;
    var len = Math.hypot(d[0], d[1]) || 1, ux = d[0] / len, uy = d[1] / len, axis = d[0] ? 'x' : 'y';
    el.hidden = false;
    if (d[0] || d[1]) blurAlong(el, axis, o.blur == null ? 4 : o.blur, ms * 0.5);
    return run(el, [
      { opacity: 0, transform: 'translate(' + d[0] + 'px,' + d[1] + 'px)', offset: 0, easing: EASE_OUT },
      { opacity: 1, transform: 'translate(' + (d[0] + ux * pull) + 'px,' + (d[1] + uy * pull) + 'px)', offset: 0.14, easing: SPRING },   // pulls back first
      { opacity: 1, transform: 'none', offset: 1 }
    ], { duration: ms, delay: o.delay || 0 });
  }
  function toggle(el, dx, dy, o) {      // move an indicator/thumb from its current offset by (dx,dy): pull back, then spring
    o = o || {}; var ms = o.duration || 560, len = Math.hypot(dx, dy) || 1, pull = o.pull == null ? 4 : o.pull;
    if (reduce.matches) return Promise.resolve();
    var axis = Math.abs(dx) >= Math.abs(dy) ? 'x' : 'y';
    if (o.blur !== 0) blurAlong(el, axis, o.blur == null ? 3 : o.blur, ms * 0.45);
    return run(el, [
      { transform: 'translate(' + (-dx) + 'px,' + (-dy) + 'px)', offset: 0, easing: EASE_OUT },
      { transform: 'translate(' + (-dx - dx / len * pull) + 'px,' + (-dy - dy / len * pull) + 'px)', offset: 0.14, easing: SPRING },
      { transform: 'none', offset: 1 }
    ], { duration: ms });
  }
  function press(el) {
    if (reduce.matches) return Promise.resolve();
    return run(el, [{ transform: 'scale(1)', easing: EASE_OUT }, { transform: 'scale(0.96)', offset: 0.2, easing: SPRING }, { transform: 'scale(1)' }], { duration: 480 });
  }
  function stagger(els, fn, o) {
    var step = ((o && o.frames) || 3) * FRAME;                 // three frames
    return Promise.all(Array.prototype.map.call(els, function (el, i) { return fn(el, i, i * step); }));
  }

  global.Motion = { enter: enter, exit: exit, spring: spring, toggle: toggle, press: press, stagger: stagger, blurAlong: blurAlong, EASE_OUT: EASE_OUT, EASE_IN: EASE_IN, SPRING: SPRING };
})(window);
