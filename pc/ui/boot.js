// Runs before first paint: applies the stored theme so there is no flash of the wrong one.
(function () {
  var pref = 'system';
  try { pref = localStorage.getItem('theme') || 'system'; } catch (e) { /* storage can be blocked */ }
  var dark = pref === 'dark' || (pref === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.dataset.themePref = pref;
})();
