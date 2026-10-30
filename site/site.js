(function () {
  'use strict';

  I18n.init();
  initLanguagePicker(document.getElementById('lang-trigger'));
  function labels() { return { system: I18n.t('theme.system'), light: I18n.t('theme.light'), dark: I18n.t('theme.dark') }; }
  SiteTheme.init({ labels: labels(), title: I18n.t('theme.label') });
  function syncMeta() {
    document.title = I18n.t('meta.title');
    var d = document.querySelector('meta[name="description"]'); if (d) d.content = I18n.t('meta.description');
    SiteTheme.relabel(labels(), I18n.t('theme.label'));
    DownloadKit.refresh();
  }
  I18n.onChange(syncMeta); syncMeta();

  var header = document.querySelector('.site-header');
  function stuck() { header.classList.toggle('is-stuck', scrollY > 8); }
  addEventListener('scroll', stuck, { passive: true }); stuck();

  /* Release asset names are set by .github/workflows/release.yml. */
  DownloadKit.init({
    repo: 'Emre1001/Phone-Cam',
    platforms: {
      windows: { match: [/pc-windows.*\.exe$/i], prefer: [], avoid: [] },
      linux: { match: [/pc-linux/i], prefer: [], avoid: [/\.sha|\.sig/i] },
      android: { match: [/\.apk$/i], prefer: [], avoid: [/debug|unsigned/i] }
    },
    t: function (k, p) { return I18n.t(k, p); },
    lang: I18n.current()
  });
  I18n.onChange(function () { DownloadKit.refresh(); });

  Reveal.init();
})();
