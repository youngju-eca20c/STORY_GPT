(() => {
  const app = document.getElementById('app');
  const body = document.body;
  const root = document.documentElement;
  const novelsCache = {};
  let novelsIndex = null;
  let readerCtx = null;

  // ---- theme / font ----
  const applyTheme = (theme) => {
    root.setAttribute('data-theme', theme);
    Storage.setTheme(theme);
    updateReaderSettingsUI();
  };
  const applyFontSize = (px) => {
    root.style.setProperty('--reader-fs', `${px}px`);
    Storage.setFontSize(px);
    updateReaderSettingsUI();
    if (readerCtx && readerCtx.relayout) readerCtx.relayout();
  };
  const updateReaderSettingsUI = () => {
    const display = document.querySelector('[data-role="font-display"]');
    if (display) display.textContent = Storage.getFontSize() + 'px';
    const theme = Storage.getTheme();
    document.querySelectorAll('[data-set-theme]').forEach((b) => {
      b.classList.toggle('active', b.getAttribute('data-set-theme') === theme);
    });
    const mode = Storage.getMode();
    document.querySelectorAll('[data-set-mode]').forEach((b) => {
      b.classList.toggle('active', b.getAttribute('data-set-mode') === mode);
    });
  };

  // ---- data ----
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

  const showLoading = () => { app.innerHTML = '<div class="loading">불러오는 중…</div>'; };

  // ---- pagination ----
  const paginate = (paragraphs, contentWidth, contentHeight, firstPageHeight = contentHeight) => {
    const measure = document.createElement('div');
    measure.className = 'reader-body measure-body';
    Object.assign(measure.style, {
      position: 'fixed',
      top: '-99999px',
      left: '0',
      width: contentWidth + 'px',
      visibility: 'hidden',
      pointerEvents: 'none',
    });
    document.body.appendChild(measure);

    const pages = [];
    let buf = [];
    const setBuf = (arr) => { measure.innerHTML = Views.paragraphHTML(arr); };
    const maxH = () => (pages.length === 0 ? firstPageHeight : contentHeight);
    const fits = () => measure.scrollHeight <= maxH() + 0.5;

    const splitLong = (text) => {
      const tokens = text.split(/(?<=[다요나죠까어\.\?!])\s+/).filter(Boolean);
      if (tokens.length <= 1) return [text];
      const out = [];
      let cur = '';
      for (const tok of tokens) {
        const candidate = cur ? cur + ' ' + tok : tok;
        setBuf([candidate]);
        if (fits()) { cur = candidate; }
        else {
          if (cur) { out.push(cur); cur = tok; }
          else { out.push(tok); cur = ''; }
        }
      }
      if (cur) out.push(cur);
      return out;
    };

    for (const para of paragraphs) {
      setBuf([...buf, para]);
      if (fits()) {
        buf.push(para);
      } else {
        if (buf.length === 0) {
          const pieces = splitLong(para);
          for (let i = 0; i < pieces.length - 1; i++) pages.push([pieces[i]]);
          buf = [pieces[pieces.length - 1]];
          setBuf(buf);
          if (!fits()) { pages.push(buf); buf = []; }
        } else {
          pages.push(buf);
          buf = [para];
        }
      }
    }
    if (buf.length) pages.push(buf);

    document.body.removeChild(measure);
    return pages;
  };

  // ---- reader setup ----
  const setupReader = ({ novel, chapter, paragraphs, prev, next }) => {
    const readerRoot = app.querySelector('[data-role="reader-root"]');
    if (!readerRoot) return null;

    // ---- UI visibility state ----
    let uiVisible = false;
    let initialHideTimer = null;
    const setUI = (visible) => {
      uiVisible = !!visible;
      body.setAttribute('data-reader-ui', uiVisible ? 'visible' : 'hidden');
    };
    const cancelInitial = () => {
      if (initialHideTimer) { clearTimeout(initialHideTimer); initialHideTimer = null; }
    };
    const toggleUI = () => {
      cancelInitial();
      setUI(!uiVisible);
    };
    setUI(true);
    initialHideTimer = setTimeout(() => {
      initialHideTimer = null;
      setUI(false);
    }, 2000);

    // ---- settings panel ----
    const openSettings = () => readerRoot.setAttribute('data-settings', 'open');
    const closeSettings = () => readerRoot.removeAttribute('data-settings');
    const settingsBackdrop = readerRoot.querySelector('[data-role="settings-backdrop"]');
    settingsBackdrop.addEventListener('click', closeSettings);

    // ---- centralized handler for reader-level actions ----
    readerRoot.addEventListener('click', (e) => {
      const action = e.target.closest('[data-action]');
      if (action && action.getAttribute('data-action') === 'open-settings') {
        e.stopPropagation();
        openSettings();
        return;
      }
      const setMode = e.target.closest('[data-set-mode]');
      if (setMode) {
        const m = setMode.getAttribute('data-set-mode');
        closeSettings();
        if (m !== Storage.getMode()) {
          Storage.setMode(m);
          router(); // re-render reader with new mode
        }
        return;
      }
      const setThemeBtn = e.target.closest('[data-set-theme]');
      if (setThemeBtn) {
        applyTheme(setThemeBtn.getAttribute('data-set-theme'));
        return;
      }
      const fontStep = e.target.closest('[data-font-step]');
      if (fontStep) {
        const delta = parseInt(fontStep.getAttribute('data-font-step'), 10) || 0;
        const v = Math.max(14, Math.min(28, Storage.getFontSize() + delta));
        applyFontSize(v);
        return;
      }
    });

    // ---- tap zones (page mode) ----
    const tapOverlay = readerRoot.querySelector('[data-role="tap-overlay"]');
    if (tapOverlay) {
      tapOverlay.addEventListener('click', (e) => {
        const z = e.target.closest('[data-tap]');
        if (!z) return;
        const action = z.getAttribute('data-tap');
        if (action === 'toggle') { toggleUI(); return; }
        if (action === 'prev') {
          cancelInitial();
          if (uiVisible) setUI(false);
          if (!ctrl.prev() && prev) {
            location.hash = `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(prev.id)}`;
          }
        }
        if (action === 'next') {
          cancelInitial();
          if (uiVisible) setUI(false);
          if (!ctrl.next() && next) {
            location.hash = `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(next.id)}`;
          }
        }
      });
    }

    // ---- scroll mode tap + scroll tracking ----
    const scrollContainer = readerRoot.querySelector('[data-role="scroll-container"]');
    if (scrollContainer) {
      scrollContainer.addEventListener('click', (e) => {
        if (e.target.closest('a, button')) return;
        toggleUI();
      });
      const updateScrollPct = () => {
        const max = scrollContainer.scrollHeight - scrollContainer.clientHeight;
        const pct = max > 0 ? Math.round((scrollContainer.scrollTop / max) * 100) : 100;
        const display = readerRoot.querySelector('[data-role="scroll-pct"]');
        if (display) display.textContent = pct + '%';
      };
      let scrollTickTs = 0;
      scrollContainer.addEventListener('scroll', () => {
        cancelInitial();
        if (uiVisible) setUI(false);
        const now = performance.now();
        if (now - scrollTickTs > 80) {
          scrollTickTs = now;
          updateScrollPct();
        }
      }, { passive: true });
      updateScrollPct();
    }

    // ---- page mode ----
    const pagesViewport = readerRoot.querySelector('[data-role="pages-viewport"]');
    const pagesStrip = readerRoot.querySelector('[data-role="pages-strip"]');
    let currentPage = 0;
    let totalPages = 1;
    let pageData = [];

    const updatePageIndicator = () => {
      const numEl = readerRoot.querySelector('[data-role="page-num"]');
      const totalEl = readerRoot.querySelector('[data-role="page-total"]');
      if (numEl) numEl.textContent = String(currentPage + 1);
      if (totalEl) totalEl.textContent = String(totalPages);
    };

    const goToPage = (idx, animate = true) => {
      if (!pagesStrip) return;
      currentPage = Math.max(0, Math.min(totalPages - 1, idx));
      if (!animate) pagesStrip.style.transition = 'none';
      pagesStrip.style.transform = `translateX(-${currentPage * 100}%)`;
      if (!animate) {
        void pagesStrip.offsetHeight;
        pagesStrip.style.transition = '';
      }
      updatePageIndicator();
    };

    const layoutPages = () => {
      if (!pagesViewport || !pagesStrip) return;
      const probe = document.createElement('div');
      probe.className = 'page';
      probe.style.visibility = 'hidden';
      pagesStrip.appendChild(probe);
      const ps = getComputedStyle(probe);
      const padL = parseFloat(ps.paddingLeft) || 0;
      const padR = parseFloat(ps.paddingRight) || 0;
      const padT = parseFloat(ps.paddingTop) || 0;
      const padB = parseFloat(ps.paddingBottom) || 0;
      const rect = probe.getBoundingClientRect();
      pagesStrip.removeChild(probe);
      const cw = Math.max(60, rect.width - padL - padR);
      const ch = Math.max(60, rect.height - padT - padB);

      // Capture the paragraph the user is currently on (excluding cover page)
      const cur = pageData[currentPage];
      const currentFirstParaIdx = cur && cur.type === 'content' ? cur.startIdx : 0;
      const wasOnCover = cur && cur.type === 'cover';

      const contentPages = paginate(paragraphs, cw, ch, ch);
      pageData = [{ type: 'cover', startIdx: -1, endIdx: -1 }];
      let cursor = 0;
      for (const arr of contentPages) {
        pageData.push({ type: 'content', paragraphs: arr, startIdx: cursor, endIdx: cursor + arr.length - 1 });
        cursor += arr.length;
      }
      totalPages = pageData.length;

      const coverHTML = novel.cover
        ? `<div class="cover-art"><img src="${Views.coverPath(novel)}" alt="${Views.escapeHTML(novel.title)}"></div>`
        : '';
      const html = pageData.map((p) => {
        if (p.type === 'cover') {
          return `<div class="page page-cover">
            ${coverHTML}
            <div class="cover-text">
              <div class="cover-novel-name">${Views.escapeHTML(novel.title)}</div>
              <h1 class="cover-chapter-name">${Views.escapeHTML(chapter.title)}</h1>
              ${novel.author ? `<div class="cover-author">${Views.escapeHTML(novel.author)}</div>` : ''}
            </div>
          </div>`;
        }
        return `<div class="page"><div class="reader-body">${Views.paragraphHTML(p.paragraphs)}</div></div>`;
      }).join('');
      pagesStrip.innerHTML = html;

      let restoreIdx;
      if (wasOnCover) {
        restoreIdx = 0;
      } else {
        restoreIdx = pageData.findIndex(
          (p) => p.type === 'content' && p.startIdx <= currentFirstParaIdx && currentFirstParaIdx <= p.endIdx
        );
      }
      currentPage = Math.max(0, restoreIdx);
      goToPage(currentPage, false);
    };

    const ctrl = {
      next: () => {
        if (!pagesViewport) return false;
        if (currentPage < totalPages - 1) { goToPage(currentPage + 1); return true; }
        return false;
      },
      prev: () => {
        if (!pagesViewport) return false;
        if (currentPage > 0) { goToPage(currentPage - 1); return true; }
        return false;
      },
      relayout: () => { if (pagesViewport) layoutPages(); },
      destroy: () => {
        cancelInitial();
        window.removeEventListener('keydown', onKey);
        window.removeEventListener('resize', onResize);
      },
    };

    // ---- keyboard ----
    const onKey = (e) => {
      if (e.target.closest('input, textarea')) return;
      if (readerRoot.getAttribute('data-settings') === 'open' && e.key === 'Escape') {
        closeSettings(); e.preventDefault(); return;
      }
      if (e.key === 'Escape' && uiVisible) { setUI(false); e.preventDefault(); return; }
      if (pagesViewport) {
        if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') {
          if (!ctrl.next() && next) location.hash = `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(next.id)}`;
          e.preventDefault();
        } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
          if (!ctrl.prev() && prev) location.hash = `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(prev.id)}`;
          e.preventDefault();
        }
      }
    };
    window.addEventListener('keydown', onKey);

    // ---- resize / orientation ----
    let resizeTimer = null;
    const onResize = () => {
      if (!pagesViewport) return;
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => layoutPages(), 150);
    };
    window.addEventListener('resize', onResize);

    // ---- touch swipe (page mode) ----
    if (pagesViewport) {
      let touchStartX = 0, touchStartY = 0, touchActive = false;
      pagesViewport.addEventListener('touchstart', (e) => {
        if (e.touches.length !== 1) return;
        touchActive = true;
        touchStartX = e.touches[0].clientX;
        touchStartY = e.touches[0].clientY;
      }, { passive: true });
      pagesViewport.addEventListener('touchend', (e) => {
        if (!touchActive) return;
        touchActive = false;
        const t = e.changedTouches[0];
        const dx = t.clientX - touchStartX;
        const dy = t.clientY - touchStartY;
        if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.2) {
          cancelInitial();
          if (dx < 0) {
            if (!ctrl.next() && next) location.hash = `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(next.id)}`;
          } else {
            if (!ctrl.prev() && prev) location.hash = `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(prev.id)}`;
          }
        }
      }, { passive: true });
    }

    if (pagesViewport) {
      requestAnimationFrame(() => requestAnimationFrame(layoutPages));
    }

    updateReaderSettingsUI();
    return ctrl;
  };

  // ---- routes ----
  const clearReaderState = () => {
    if (readerCtx && readerCtx.destroy) readerCtx.destroy();
    readerCtx = null;
    body.removeAttribute('data-page');
    body.removeAttribute('data-reader-ui');
  };

  const routeHome = async () => {
    clearReaderState();
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
    clearReaderState();
    showLoading();
    try {
      const novel = await loadNovel(id);
      const progress = Storage.getNovelProgress(id);
      app.innerHTML = Views.renderNovel(novel, progress);
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
    clearReaderState();
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

      const paragraphs = Views.parseParagraphs(text, chapter.title);
      const mode = Storage.getMode();

      body.setAttribute('data-page', 'reader');
      app.innerHTML = Views.renderReader(novel, chapter, paragraphs, prev, next, mode);
      Storage.setProgress(novelId, chapterId);
      window.scrollTo(0, 0);

      readerCtx = setupReader({ novel, chapter, paragraphs, prev, next });
    } catch (e) {
      console.error(e);
      clearReaderState();
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

  // ---- init ----
  applyTheme(Storage.getTheme());
  applyFontSize(Storage.getFontSize());

  document.getElementById('themeBtn').addEventListener('click', () => {
    const next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    applyTheme(next);
  });
  document.getElementById('fontIncBtn').addEventListener('click', () => {
    applyFontSize(Math.min(28, Storage.getFontSize() + 1));
  });
  document.getElementById('fontDecBtn').addEventListener('click', () => {
    applyFontSize(Math.max(14, Storage.getFontSize() - 1));
  });

  window.addEventListener('hashchange', router);
  router();
})();
