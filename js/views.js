const Views = (() => {
  const APP_VERSION = '0.9.0';
  const escapeHTML = (s) => String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

  const parseParagraphs = (text, dropFirstIfEquals) => {
    let parts = text
      .replace(/\r\n/g, '\n')
      .split(/\n\s*\n/)
      .map((p) => p.trim())
      .filter(Boolean);
    if (dropFirstIfEquals && parts[0] && parts[0] === dropFirstIfEquals.trim()) {
      parts = parts.slice(1);
    }
    return parts;
  };

  const paragraphHTML = (paragraphs) =>
    paragraphs
      .map((p) => `<p>${escapeHTML(p).replace(/\n/g, '<br>')}</p>`)
      .join('');

  const coverPath = (novel) =>
    novel && novel.cover ? `novels/${encodeURIComponent(novel.id)}/${encodeURIComponent(novel.cover)}` : '';

  const renderCover = (novel, extraClass = '') => {
    const cls = `novel-cover${novel && novel.cover ? ' has-image' : ''}${extraClass ? ' ' + extraClass : ''}`;
    if (novel && novel.cover) {
      return `<div class="${cls}"><img src="${coverPath(novel)}" alt="${escapeHTML(novel.title)}" loading="lazy"></div>`;
    }
    return `<div class="${cls}">${escapeHTML(novel.title)}</div>`;
  };

  const renderHome = (novels) => {
    if (!novels.length) {
      return `<div class="empty-state">
        <h2>등록된 작품이 없습니다</h2>
        <p>data/novels.json에 작품을 추가해보세요.</p>
      </div>`;
    }
    const cards = novels.map((n) => {
      const tags = (n.tags || []).map((t) => `<span class="tag">${escapeHTML(t)}</span>`).join('');
      return `
        <a class="novel-card" href="#/novel/${encodeURIComponent(n.id)}">
          ${renderCover(n)}
          <h3 class="novel-title">${escapeHTML(n.title)}</h3>
          <div class="novel-meta">${tags}</div>
          <p class="novel-desc">${escapeHTML(n.description || '')}</p>
          <div class="novel-status">${escapeHTML(n.author || '')}${n.author && n.status ? ' · ' : ''}${escapeHTML(n.status || '')}</div>
        </a>`;
    }).join('');
    const loreHref = novels.length === 1
      ? `#/worldbuilding/${encodeURIComponent(novels[0].id)}`
      : `#/worldbuilding`;
    return `
      <div class="home-header">
        <h1 class="page-title">소설 서재</h1>
        <a class="lore-link" href="${loreHref}">
          <span class="lore-link-icon">📖</span>
          <span>설정집</span>
        </a>
      </div>
      <p class="page-subtitle">읽고 싶은 작품을 골라보세요.</p>
      <div class="novel-grid">${cards}</div>
      <div class="home-version">v${APP_VERSION}</div>
    `;
  };

  const renderWorldbuildingHub = (novels) => {
    if (!novels.length) {
      return `<div class="empty-state">
        <h2>등록된 작품이 없습니다</h2>
        <p><a href="#/">서재로 돌아가기</a></p>
      </div>`;
    }
    const items = novels.map((n) => `
      <a class="lore-hub-card" href="#/worldbuilding/${encodeURIComponent(n.id)}">
        ${renderCover(n, 'lore-hub-cover')}
        <div class="lore-hub-info">
          <h3>${escapeHTML(n.title)}</h3>
          <div class="lore-hub-meta">설정집 보기 →</div>
        </div>
      </a>
    `).join('');
    return `
      <div class="reader-breadcrumb"><a href="#/">← 서재로</a></div>
      <header class="lore-header">
        <div class="lore-eyebrow">설정집</div>
        <h1 class="lore-title">작품 선택</h1>
      </header>
      <div class="lore-hub-grid">${items}</div>
    `;
  };

  const renderCharacterCard = (c) => `
    <article class="character-card">
      <div class="character-header">
        <h3 class="character-name">${escapeHTML(c.name)}</h3>
        <div class="character-role">${escapeHTML(c.role || '')}</div>
      </div>
      ${c.tags && c.tags.length
        ? `<div class="character-tags">${c.tags.map((t) => `<span class="tag">${escapeHTML(t)}</span>`).join('')}</div>`
        : ''}
      <p class="character-body">${escapeHTML(c.body || '')}</p>
    </article>`;

  const renderFutureCard = (f) => {
    const sections = [];
    if (f.summary) sections.push(`<p class="future-summary">${escapeHTML(f.summary)}</p>`);
    if (f.situation) sections.push(`<div class="future-section"><div class="future-section-label">상황</div><p>${escapeHTML(f.situation)}</p></div>`);
    if (f.beats) sections.push(`<div class="future-section"><div class="future-section-label">주요 장면</div><p>${escapeHTML(f.beats)}</p></div>`);
    if (f.rewards) sections.push(`<div class="future-section"><div class="future-section-label">보상 · 떡밥</div><p>${escapeHTML(f.rewards)}</p></div>`);
    const newChars = (f.new_characters || []);
    if (newChars.length) {
      sections.push(`
        <div class="future-section">
          <div class="future-section-label">새 등장인물</div>
          <div class="character-grid future-char-grid">
            ${newChars.map(renderCharacterCard).join('')}
          </div>
        </div>`);
    }
    return `
      <article class="future-card">
        <h3 class="future-title">${escapeHTML(f.title)}</h3>
        ${sections.join('')}
      </article>`;
  };

  const renderWorldbuilding = (novel, lore) => {
    const world = (lore && lore.world) || [];
    const characters = (lore && lore.characters) || [];
    const future = (lore && lore.future) || [];

    if (!world.length && !characters.length && !future.length) {
      return `
        <div class="reader-breadcrumb"><a href="#/">← 서재로</a></div>
        <header class="lore-header">
          <div class="lore-eyebrow">설정집</div>
          <h1 class="lore-title">${escapeHTML(novel.title)}</h1>
        </header>
        <div class="empty-state">
          <h2>설정집이 비어 있습니다</h2>
          <p>novels/${escapeHTML(novel.id)}/worldbuilding.json 파일을 작성해 보세요.</p>
        </div>`;
    }

    const tabDefs = [];
    if (world.length) tabDefs.push({ id: 'world', label: '세계관' });
    if (characters.length) tabDefs.push({ id: 'characters', label: '등장인물' });
    if (future.length) tabDefs.push({ id: 'future', label: '미래계획' });
    const defaultTab = tabDefs[0].id;

    const worldPane = world.length
      ? `<section class="lore-pane" data-section="world"${defaultTab === 'world' ? '' : ' hidden'}>
          <div class="lore-grid">
            ${world.map((w) => `
              <article class="lore-card">
                <h3>${escapeHTML(w.title)}</h3>
                <p>${escapeHTML(w.body)}</p>
              </article>
            `).join('')}
          </div>
        </section>`
      : '';

    const charactersPane = characters.length
      ? `<section class="lore-pane" data-section="characters"${defaultTab === 'characters' ? '' : ' hidden'}>
          <div class="character-grid">
            ${characters.map(renderCharacterCard).join('')}
          </div>
        </section>`
      : '';

    const futurePane = future.length
      ? `<section class="lore-pane" data-section="future"${defaultTab === 'future' ? '' : ' hidden'}>
          <div class="future-warning">⚠ 향후 회차의 스포일러를 포함합니다. 읽기 전이라면 주의하세요.</div>
          <div class="future-list">
            ${future.map(renderFutureCard).join('')}
          </div>
        </section>`
      : '';

    const tabs = `
      <div class="lore-tabs" role="tablist" data-role="lore-tabs">
        ${tabDefs.map((t) => `<button class="lore-tab${t.id === defaultTab ? ' active' : ''}" role="tab" data-lore-tab="${t.id}">${escapeHTML(t.label)}</button>`).join('')}
      </div>`;

    return `
      <div class="reader-breadcrumb"><a href="#/">← 서재로</a></div>
      <header class="lore-header">
        <div class="lore-eyebrow">설정집</div>
        <h1 class="lore-title">${escapeHTML(novel.title)}</h1>
      </header>
      ${tabs}
      ${worldPane}
      ${charactersPane}
      ${futurePane}
    `;
  };

  const renderNovel = (novel, progressChapterId) => {
    const tags = (novel.tags || []).map((t) => `<span class="tag">${escapeHTML(t)}</span>`).join('');
    const chapters = novel.chapters || [];
    const continueChapter = progressChapterId
      ? chapters.find((c) => c.id === progressChapterId)
      : chapters[0];

    const continueBtn = continueChapter
      ? `<a class="continue-btn" href="#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(continueChapter.id)}">
          ${progressChapterId ? '이어 읽기' : '처음부터 읽기'} →
        </a>`
      : '';

    const items = chapters.map((c) => {
      const isCurrent = c.id === progressChapterId;
      return `
        <li class="chapter-item" data-href="#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(c.id)}">
          <div class="chapter-info">
            <div class="chapter-name">${escapeHTML(c.title)}</div>
            ${c.published ? `<div class="chapter-sub">${escapeHTML(c.published)}</div>` : ''}
          </div>
          ${isCurrent ? '<span class="chapter-badge">최근 읽음</span>' : ''}
        </li>`;
    }).join('');

    return `
      <div class="reader-breadcrumb"><a href="#/">← 서재로</a></div>
      <section class="novel-hero">
        ${renderCover(novel)}
        <div class="novel-hero-info">
          <h1>${escapeHTML(novel.title)}</h1>
          <div class="author">${escapeHTML(novel.author || '작자 미상')}${novel.status ? ' · ' + escapeHTML(novel.status) : ''}</div>
          <div class="novel-meta">${tags}</div>
          <p class="description">${escapeHTML(novel.description || '')}</p>
          ${continueBtn}
        </div>
      </section>
      <h2 class="section-title">
        <span>회차 목록</span>
        <span class="chapter-count">총 ${chapters.length}화</span>
      </h2>
      <ul class="chapter-list">${items}</ul>
    `;
  };

  const renderReader = (novel, chapter, paragraphs, prevChapter, nextChapter, mode) => {
    const prevHref = prevChapter
      ? `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(prevChapter.id)}`
      : '';
    const nextHref = nextChapter
      ? `#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(nextChapter.id)}`
      : '';

    const coverBlock = novel.cover
      ? `<div class="reader-cover-image"><img src="${coverPath(novel)}" alt="${escapeHTML(novel.title)}"></div>`
      : '';
    const scrollContent = `
      <div class="reader-scroll" data-role="scroll-container">
        <div class="reader-scroll-inner">
          ${coverBlock}
          <h1 class="reader-title">${escapeHTML(chapter.title)}</h1>
          <div class="reader-novel-label">${escapeHTML(novel.title)}</div>
          <div class="reader-body" data-role="scroll-body">${paragraphHTML(paragraphs)}</div>
          <div class="reader-end-nav">
            ${prevHref
              ? `<a class="end-btn" href="${prevHref}">← 이전 화</a>`
              : '<span class="end-btn disabled">이전 화 없음</span>'}
            ${nextHref
              ? `<a class="end-btn next" href="${nextHref}">다음 화 →</a>`
              : '<span class="end-btn disabled">마지막 화입니다</span>'}
          </div>
        </div>
      </div>`;

    const pageContent = `
      <div class="reader-pages" data-role="pages-viewport">
        <div class="pages-strip" data-role="pages-strip"></div>
        <div class="tap-overlay" data-role="tap-overlay">
          <button class="tap-zone left" data-tap="next" aria-label="다음 페이지"></button>
          <button class="tap-zone center" data-tap="toggle" aria-label="컨트롤 토글"></button>
          <button class="tap-zone right" data-tap="next" aria-label="다음 페이지"></button>
        </div>
      </div>`;

    return `
      <div class="reader-fullscreen" data-mode="${mode}" data-role="reader-root">
        ${mode === 'page' ? pageContent : scrollContent}

        <header class="reader-overlay top" data-role="overlay-top">
          <a class="overlay-btn back" href="#/novel/${encodeURIComponent(novel.id)}" aria-label="회차 목록으로">←</a>
          <div class="overlay-title">
            <div class="overlay-novel">${escapeHTML(novel.title)}</div>
            <div class="overlay-chapter">${escapeHTML(chapter.title)}</div>
          </div>
          <button class="overlay-btn" data-action="open-settings" aria-label="설정">⚙</button>
        </header>

        <footer class="reader-overlay bottom" data-role="overlay-bottom">
          <div class="reader-progress">
            <div class="progress-meta">
              <span class="progress-chapter">${escapeHTML(chapter.title)}</span>
              <span class="progress-position" data-role="progress-position">—</span>
            </div>
            <div class="progress-bar" data-role="progress-bar" role="slider" aria-label="읽기 진행도" tabindex="0">
              <div class="progress-track"></div>
              <div class="progress-fill" data-role="progress-fill"></div>
              <div class="progress-handle" data-role="progress-handle"></div>
            </div>
          </div>
          <div class="overlay-chapter-nav">
            ${prevHref
              ? `<a class="overlay-chap-btn" href="${prevHref}">← 이전 화</a>`
              : '<span class="overlay-chap-btn disabled">← 이전 화</span>'}
            ${nextHref
              ? `<a class="overlay-chap-btn" href="${nextHref}">다음 화 →</a>`
              : '<span class="overlay-chap-btn disabled">다음 화 →</span>'}
          </div>
        </footer>

        <div class="reader-settings-backdrop" data-role="settings-backdrop"></div>
        <div class="reader-settings-panel" data-role="settings-panel">
          <div class="settings-handle"></div>
          <div class="settings-row">
            <span class="settings-label">읽기 모드</span>
            <div class="settings-options seg">
              <button data-set-mode="scroll" class="seg-btn ${mode === 'scroll' ? 'active' : ''}">스크롤</button>
              <button data-set-mode="page" class="seg-btn ${mode === 'page' ? 'active' : ''}">페이지</button>
            </div>
          </div>
          <div class="settings-row">
            <span class="settings-label">테마</span>
            <div class="settings-options seg">
              <button data-set-theme="light" class="seg-btn" data-role="theme-light">라이트</button>
              <button data-set-theme="dark" class="seg-btn" data-role="theme-dark">다크</button>
            </div>
          </div>
          <div class="settings-row settings-row-stacked">
            <span class="settings-label">글꼴</span>
            <div class="settings-options seg seg-stretch">
              <button data-set-font="serif" class="seg-btn font-preview-serif">명조</button>
              <button data-set-font="sans" class="seg-btn font-preview-sans">고딕</button>
              <button data-set-font="myeongjo" class="seg-btn font-preview-myeongjo">나눔명조</button>
            </div>
          </div>
          <div class="settings-row">
            <span class="settings-label">글자 크기</span>
            <div class="settings-options">
              <button class="step-btn" data-font-step="-1">A−</button>
              <span class="step-val" data-role="font-display">${Storage.getFontSize()}px</span>
              <button class="step-btn" data-font-step="1">A+</button>
            </div>
          </div>
        </div>
      </div>
    `;
  };

  const renderError = (message, hint) => `
    <div class="empty-state">
      <h2>${escapeHTML(message)}</h2>
      ${hint ? `<p>${escapeHTML(hint)}</p>` : ''}
      <p><a href="#/">서재로 돌아가기</a></p>
    </div>
  `;

  return { renderHome, renderNovel, renderReader, renderError, parseParagraphs, paragraphHTML, escapeHTML, coverPath, renderWorldbuilding, renderWorldbuildingHub };
})();
