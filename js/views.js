const Views = (() => {
  const escapeHTML = (s) => String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');

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
          <div class="novel-cover">${escapeHTML(n.title)}</div>
          <h3 class="novel-title">${escapeHTML(n.title)}</h3>
          <div class="novel-meta">${tags}</div>
          <p class="novel-desc">${escapeHTML(n.description || '')}</p>
          <div class="novel-status">${escapeHTML(n.author || '')}${n.author && n.status ? ' · ' : ''}${escapeHTML(n.status || '')}</div>
        </a>`;
    }).join('');
    return `
      <h1 class="page-title">소설 서재</h1>
      <p class="page-subtitle">읽고 싶은 작품을 골라보세요.</p>
      <div class="novel-grid">${cards}</div>
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
        <div class="novel-cover">${escapeHTML(novel.title)}</div>
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

  const renderReader = (novel, chapter, text, prevChapter, nextChapter) => {
    const paragraphs = text
      .replace(/\r\n/g, '\n')
      .split(/\n\s*\n/)
      .map((p) => p.trim())
      .filter(Boolean)
      .map((p) => `<p>${escapeHTML(p).replace(/\n/g, '<br>')}</p>`)
      .join('');

    const prevBtn = prevChapter
      ? `<a class="nav-btn prev" href="#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(prevChapter.id)}">
          <span class="nav-btn-label">← 이전 화</span>
          <span class="nav-btn-title">${escapeHTML(prevChapter.title)}</span>
        </a>`
      : `<button class="nav-btn prev" disabled>
          <span class="nav-btn-label">이전 화 없음</span>
          <span class="nav-btn-title">—</span>
        </button>`;

    const nextBtn = nextChapter
      ? `<a class="nav-btn next" href="#/read/${encodeURIComponent(novel.id)}/${encodeURIComponent(nextChapter.id)}">
          <span class="nav-btn-label">다음 화 →</span>
          <span class="nav-btn-title">${escapeHTML(nextChapter.title)}</span>
        </a>`
      : `<button class="nav-btn next" disabled>
          <span class="nav-btn-label">마지막 화입니다</span>
          <span class="nav-btn-title">—</span>
        </button>`;

    return `
      <article class="reader">
        <div class="reader-breadcrumb">
          <a href="#/novel/${encodeURIComponent(novel.id)}">← ${escapeHTML(novel.title)}</a>
        </div>
        <h1 class="reader-title">${escapeHTML(chapter.title)}</h1>
        <div class="reader-novel">${escapeHTML(novel.title)}</div>
        <div class="reader-body">${paragraphs}</div>
        <nav class="reader-nav">${prevBtn}${nextBtn}</nav>
      </article>
    `;
  };

  const renderError = (message, hint) => `
    <div class="empty-state">
      <h2>${escapeHTML(message)}</h2>
      ${hint ? `<p>${escapeHTML(hint)}</p>` : ''}
      <p><a href="#/">서재로 돌아가기</a></p>
    </div>
  `;

  return { renderHome, renderNovel, renderReader, renderError };
})();
