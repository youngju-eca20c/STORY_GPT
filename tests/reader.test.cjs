const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const context = vm.createContext({ Storage: { getFontSize: () => 18 } });
vm.runInContext(fs.readFileSync(path.join(root, 'js/views.js'), 'utf8') + '\nthis.Views = Views;', context);
const { Views } = context;

test('the original plain text format keeps title removal, paragraph breaks and HTML escaping', () => {
  const paragraphs = Views.parseParagraphs('1화 — 시작\r\n\r\n첫 문장.\r\n이어지는 줄.\r\n\r\n"대사" & <기록>\r\n', '1화 — 시작');
  assert.equal(Views.paragraphHTML(paragraphs), '<p>첫 문장.<br>이어지는 줄.</p><p>&quot;대사&quot; &amp; &lt;기록&gt;</p>');
  assert.equal(Views.paragraphHTML(Views.parseParagraphs('제목 없는 원고\n\n둘째 문단', '다른 제목')), '<p>제목 없는 원고</p><p>둘째 문단</p>');
  assert.equal(Views.paragraphHTML(Views.parseParagraphs('\n\n', '빈 회차')), '');
});

test('current plain manuscripts remain readable without interpreting HTML as markup', () => {
  const index = JSON.parse(fs.readFileSync(path.join(root, 'data/novels.json'), 'utf8'));
  for (const novel of index.novels) {
    const meta = JSON.parse(fs.readFileSync(path.join(root, 'novels', novel.id, 'meta.json'), 'utf8'));
    for (const chapter of meta.chapters) {
      const body = fs.readFileSync(path.join(root, 'novels', novel.id, chapter.file), 'utf8');
      const parsed = Views.parseParagraphs(body, chapter.title);
      const html = Views.paragraphHTML(parsed);
      if (parsed.length) assert.ok(html.startsWith('<p>') || html.startsWith('<figure'), chapter.title);
      if (!parsed.some(Views.illustrationOf)) assert.ok(!html.includes('<figure'), chapter.title);
      assert.ok(!html.includes('<script'), chapter.title);
      assert.equal((html.match(/<p>/g) || []).length + (html.match(/<figure/g) || []).length, parsed.length);
    }
  }
});

test('standalone illustration paragraphs support Korean names and alt text', () => {
  const parsed = Views.parseParagraphs('앞 문장\n\n![세라의 모습](illust/세라 사진.webp)\n\n뒤 문장');
  assert.equal(Views.paragraphHTML(parsed), '<p>앞 문장</p><figure class="reader-illustration"><img src="illust/%EC%84%B8%EB%9D%BC%20%EC%82%AC%EC%A7%84.webp" alt="세라의 모습" decoding="async"></figure><p>뒤 문장</p>');
  assert.equal(Views.illustrationOf('![일러스트](illust/a.JPEG)').path, 'illust/a.JPEG');
});

test('external, encoded and escaping illustration paths stay escaped literal text', () => {
  for (const value of ['https://evil.test/a.png', '//evil.test/a.png', 'illust/../a.png', 'illust/%2e%2e/a.png', 'illust/%252e%252e.png', 'illust/a.svg', 'illust/a.png?x=1', 'illust/a.png#x', 'illust/a\\b.png']) {
    const text = `![<그림>]( ${value})`.replace('( ', '(');
    assert.equal(Views.illustrationOf(text), null, value);
    assert.ok(!Views.paragraphHTML([text]).includes('<img'), value);
  }
  assert.equal(Views.illustrationOf('앞 문장 ![그림](illust/a.png)'), null);
});

test('chapter visibility is separate from existing publication dates', () => {
  const novel = { id: 'sample', title: '작품', chapters: [
    { id: '001', title: '기존 회차', published: '2026-09-15' },
    { id: '002', title: '비공개 원고', visibility: 'private' },
    { id: '003', title: '작성 중', visibility: 'draft' },
    { id: '004', title: '공개 회차', visibility: 'public' },
  ] };
  const html = Views.renderNovel(novel, '002');
  assert.ok(html.includes('기존 회차'));
  assert.ok(html.includes('2026-09-15'));
  assert.ok(html.includes('총 2화'));
  assert.ok(!html.includes('비공개 원고'));
  assert.ok(!html.includes('작성 중'));
  assert.ok(!html.includes('#/read/sample/002'));
});

test('scroll preview reuses reader covers, font settings and inline images', () => {
  const html = Views.renderReader({ id: 'sample', title: '작품', cover: 'cover.png' }, { id: '001', title: '회차' }, ['문장', '![그림](illust/a.png)'], null, null, 'scroll');
  for (const expected of ['data-mode="scroll"', 'novels/sample/cover.png', 'reader-illustration', 'data-set-theme="dark"', 'data-set-font="myeongjo"']) assert.ok(html.includes(expected), expected);
});

test('direct private novel/chapter routes never request their manuscript bodies', async () => {
  const requested = [];
  const documents = {
    'data/novels.json': { novels: [{ id: 'private-novel', title: '숨김', visibility: 'private' }, { id: 'visible', title: '공개' }] },
    'novels/visible/meta.json': { title: '공개', chapters: [
      { id: '001', title: '공개 회차', file: 'chapters/001.txt' },
      { id: '002', title: '숨김 회차', file: 'chapters/002.txt', visibility: 'private' },
      { id: '003', title: '초고', file: 'chapters/003.txt', visibility: 'draft' },
    ] },
  };
  const app = { innerHTML: '' };
  const runtime = vm.createContext({ Views, Storage: {}, console: { error() {} },
    document: { getElementById: () => app, body: { removeAttribute() {} }, documentElement: {} },
    fetch: async (url) => {
      requested.push(url);
      assert.ok(documents[url], `unexpected request: ${url}`);
      return { ok: true, json: async () => documents[url] };
    },
  });
  const source = fs.readFileSync(path.join(root, 'js/app.js'), 'utf8');
  vm.runInContext(source.slice(0, source.indexOf('  // ---- init ----')) + '\nthis.testAPI = { loadIndex, loadNovel, routeRead };\n})();', runtime);
  assert.equal((await runtime.testAPI.loadIndex()).length, 1);
  await assert.rejects(runtime.testAPI.loadNovel('private-novel'));
  assert.equal((await runtime.testAPI.loadNovel('visible')).chapters.length, 1);
  await runtime.testAPI.routeRead('visible', '002');
  await runtime.testAPI.routeRead('visible', '003');
  assert.ok(app.innerHTML.includes('회차를 불러올 수 없습니다'));
  assert.ok(!requested.some((url) => url.endsWith('.txt')));
});
