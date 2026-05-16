const Storage = (() => {
  const KEY_THEME = 'reader.theme';
  const KEY_FONT = 'reader.fontSize';
  const KEY_PROGRESS = 'reader.progress';

  const getTheme = () => localStorage.getItem(KEY_THEME) || 'light';
  const setTheme = (v) => localStorage.setItem(KEY_THEME, v);

  const getFontSize = () => {
    const v = parseInt(localStorage.getItem(KEY_FONT), 10);
    return Number.isFinite(v) ? v : 18;
  };
  const setFontSize = (v) => localStorage.setItem(KEY_FONT, String(v));

  const getProgress = () => {
    try { return JSON.parse(localStorage.getItem(KEY_PROGRESS) || '{}'); }
    catch { return {}; }
  };
  const setProgress = (novelId, chapterId) => {
    const all = getProgress();
    all[novelId] = chapterId;
    localStorage.setItem(KEY_PROGRESS, JSON.stringify(all));
  };
  const getNovelProgress = (novelId) => getProgress()[novelId] || null;

  return { getTheme, setTheme, getFontSize, setFontSize, getProgress, setProgress, getNovelProgress };
})();
