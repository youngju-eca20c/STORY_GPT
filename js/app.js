(() => {
  const app = document.getElementById('app');
  const novelsCache = {};
  let novelsIndex = null;

  const applyTheme = (theme) => {
    document.documentElement.setAttribute('data-theme', theme);
    Storage.setTheme(theme);
  };

  const applyFontSize = (px) => {
    document.documentElement.style.setProperty('--reader-fs', `${px}px`);
    Storage.setFontSize(px);
  };

  const fetchJSON = async (url) => {
    const res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`);
    return res.json();
  };

  const fetchText = async (url) => {
    const res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`);
    return res.text();
  };

  const loadIndex = async () => {
    if (novelsIndex) return novelsIndex;
    const data = await fetchJSON('data/novels.json');
    novelsIndex = data.novels || [];
    return novelsIndex;
  };

  const loadNovel = async (id) => {
    if (novelsCache[id]) return novelsCache[id];
    const index = await loadIndex();
    const ref = index.find((n) => n.id === id);
    if (!ref) throw new Error('NOT_FOUND_INDEX');
    const meta = await fetchJSON(`novels/${id}/meta.json`);
    novelsCache[id] = { ...ref, ...meta, id };
    return novelsCache[id];
  };

  const showLoading = () => {
    app.innerHTML = '<div class="loading">불러오는 중…</div>';
  };

  const routeHome = async () => {
    showLoading();
    try {
      const novels = await loadIndex();
      app.innerHTML = Views.renderHome(novels);
    } catch (e) {
      console.error(e);
      app.innerHTML = Views.renderError('작품 목록을 불러올 수 없습니다', 'data/novels.json 파일을 확인하세요.');
    }
  };

  const routeNovel = async (id) => {
    showLoading();
    try {
      const novel = await loadNovel(id);
      const progress = Storage.getNovelProgress(id);
      app.innerHTML = Views.renderNovel(novel, progress);
      // Make chapter rows clickable
      app.querySelectorAll('.chapter-item').forEach((el) => {
        el.addEventListener('click', () => {
          const href = el.getAttribute('data-href');
          if (href) location.hash = href;
        });
      });
    } catch (e) {
      console.error(e);
      app.innerHTML = Views.renderError('작품을 찾을 수 없습니다', '주소를 확인해주세요.');
    }
  };

  const routeRead = async (novelId, chapterId) => {
    showLoading();
    try {
      const novel = await loadNovel(novelId);
      const chapters = novel.chapters || [];
      const idx = chapters.findIndex((c) => c.id === chapterId);
      if (idx < 0) throw new Error('NO_CHAPTER');
      const chapter = chapters[idx];
      const text = await fetchText(`novels/${novelId}/${chapter.file}`);
      const prev = idx > 0 ? chapters[idx - 1] : null;
      const next = idx < chapters.length - 1 ? chapters[idx + 1] : null;
      app.innerHTML = Views.renderReader(novel, chapter, text, prev, next);
      Storage.setProgress(novelId, chapterId);
      window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });
    } catch (e) {
      console.error(e);
      app.innerHTML = Views.renderError('회차를 불러올 수 없습니다', '파일이 존재하는지 확인해주세요.');
    }
  };

  const router = () => {
    const hash = location.hash.replace(/^#/, '') || '/';
    const parts = hash.split('/').filter(Boolean);
    if (parts.length === 0) return routeHome();
    if (parts[0] === 'novel' && parts[1]) return routeNovel(decodeURIComponent(parts[1]));
    if (parts[0] === 'read' && parts[1] && parts[2]) {
      return routeRead(decodeURIComponent(parts[1]), decodeURIComponent(parts[2]));
    }
    return routeHome();
  };

  // ---- Init ----
  applyTheme(Storage.getTheme());
  applyFontSize(Storage.getFontSize());

  document.getElementById('themeBtn').addEventListener('click', () => {
    const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    applyTheme(next);
  });

  document.getElementById('fontIncBtn').addEventListener('click', () => {
    const v = Math.min(28, Storage.getFontSize() + 1);
    applyFontSize(v);
  });
  document.getElementById('fontDecBtn').addEventListener('click', () => {
    const v = Math.max(14, Storage.getFontSize() - 1);
    applyFontSize(v);
  });

  window.addEventListener('hashchange', router);
  router();
})();
