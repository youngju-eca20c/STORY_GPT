const Storage = (() => {
  const KEY_THEME = 'reader.theme';
  const KEY_FONT = 'reader.fontSize';
  const KEY_FONT_FAMILY = 'reader.fontFamily';
  const KEY_PROGRESS = 'reader.progress';
  const KEY_MODE = 'reader.mode';

  return {
    getTheme: () => localStorage.getItem(KEY_THEME) || 'light',
    setTheme: (v) => localStorage.setItem(KEY_THEME, v),
    getFontSize: () => {
      const v = parseInt(localStorage.getItem(KEY_FONT), 10);
      return Number.isFinite(v) ? v : 18;
    },
    setFontSize: (v) => localStorage.setItem(KEY_FONT, String(v)),
    getFontFamily: () => localStorage.getItem(KEY_FONT_FAMILY) || 'serif',
    setFontFamily: (v) => localStorage.setItem(KEY_FONT_FAMILY, v),
    getProgress: () => {
      try { return JSON.parse(localStorage.getItem(KEY_PROGRESS) || '{}'); }
      catch { return {}; }
    },
    setProgress: (novelId, chapterId) => {
      let all = {};
      try { all = JSON.parse(localStorage.getItem(KEY_PROGRESS) || '{}'); } catch {}
      all[novelId] = chapterId;
      localStorage.setItem(KEY_PROGRESS, JSON.stringify(all));
    },
    getNovelProgress: (novelId) => {
      try {
        const all = JSON.parse(localStorage.getItem(KEY_PROGRESS) || '{}');
        return all[novelId] || null;
      } catch { return null; }
    },
    getMode: () => localStorage.getItem(KEY_MODE) || 'page',
    setMode: (v) => localStorage.setItem(KEY_MODE, v),
  };
})();
