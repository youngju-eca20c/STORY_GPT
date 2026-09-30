# STORY GPT Editor 저장/공개 활성화

현재 독자 사이트 주소 `https://youngju-eca20c.github.io/STORY_GPT/#/`를 그대로 사용한다. 아래는 최초 활성화와 재설정 절차다. 기존 배포 설정은 legacy / main / 루트(/)였으며 같은 사이트 주소에서 GitHub Actions 방식으로 전환한다.

## 권장 활성화 순서

1. 기존 사이트의 정상 동작을 확인한다. 기존 main과 기존 Pages 설정을 복구 지점으로 기록한다.
2. `android-editor/`, 독자 화면의 최소 확장, `scripts/`, `.github/workflows/`를 main에 반영한다. 이 시점에 기존 원고/이미지는 수정하지 않는다.
3. 앱 설정에서 GitHub 계정 `youngju-eca20c`, 저장소 `STORY_GPT`, 저장용 branch `editor-drafts`를 사용한다. 앱이 main의 현재 내용을 기반으로 저장용 branch를 생성하고 연결을 확인한다. 저장은 이 branch에만 한다. 이미 branch가 있으면 새로 덮어쓰지 않는다.
4. 저장소 **Settings → Pages → Build and deployment → Source**를 **GitHub Actions**로 바꾼다. 기존 branch 기반 배포를 계속 사용하면 앱의 저장/공개 분리가 성립하지 않는다. 이 설정 변경만으로 새 원고를 공개하는 것은 아니다.
5. 저장소 **Actions**에서 `Publish STORY GPT`를 한 번 수동 실행한다. `content_branch`는 `editor-drafts`, `content_ref`는 비워 두거나 공개할 저장 Commit의 40자리 SHA를 지정한다. 앱의 지금 공개도 같은 workflow를 main에서 dispatch한다. 예약 공개는 기본 editor-drafts 브랜치를 사용하므로 다른 원고 브랜치를 지정했다면 workflow의 기본 브랜치도 같은 값으로 수정해야 한다. 필요하면 github-pages 환경의 보호 규칙이 main 배포를 허용하는지 확인한다.
6. 성공한 사이트의 `publication.json`과 작품/읽기/설정집을 확인한다. 이후 저장 버튼은 독자 사이트를 변경하지 않으며 지금 공개 또는 일일 스케줄이 저장용 branch의 공개 콘텐츠만 반영한다.

`editor-drafts`가 없거나 유효하지 않으면 배포는 실패하며 기존 Pages 버전이 유지된다. main으로 자동 대체하지 않는다. 최초 분리 작업의 원고는 main과 같으므로 첫 배포에서 기존 작품이 유지된다.

## Token 권한과 개인 설정

Fine-grained Personal Access Token이면 해당 저장소에 **Contents: Read and write**, **Actions: Read and write**를 지정한다. Contents는 원고/이미지 Commit와 새 저장용 branch 생성, Actions는 지금 공개와 실행 상태 확인에 필요하다. Pages Source 전환은 저장소 설정에서 한 번 수행한다. 기기 저장소에만 PAT를 저장하며 APK/코드/로그에 포함하지 않는다. token의 만료나 권한 부족은 앱의 연결 테스트/저장 오류에서 확인한다. 고전 PAT를 사용하면 `repo`와 workflow 권한 및 계정/조직 정책을 확인한다. 이 앱이 기존 workflow를 수정하지 않으면 원고 저장 자체에 workflow 권한은 필요하지 않다.

## 공개 산출물

`scripts/build_publication.py`는 읽기 전용으로 두 입력을 받는다.

- reader: main의 `index.html`, `css/style.css`, `js/storage.js`, `js/views.js`, `js/app.js`
- content: 확정한 저장용 Commit의 `data/novels.json`, 공개 작품 메타데이터/원고/설정집과 실제 참조 이미지

출력은 비어 있는 별도 폴더에만 만든다. 원본 입력과 Git History를 수정하지 않는다. `visibility` 누락 또는 `public`만 공개하며 `private`/`draft` 작품/회차는 목록과 파일 모두 빠진다. 제목/경로/배열/중복 ID/이미지 참조가 잘못되면 배포 전에 실패한다. 오래된 비공개/삭제 파일을 남기지 않도록 전체 저장소 폴더를 그대로 Pages에 올리지 않는다. 공용 설정집은 기존 동작대로 포함한다.

`publication.json`은 `content_sha`, `reader_sha`, `request_id`, 공개 작품/회차 수를 기록한다. 앱은 Actions 실행의 성공과 같은 요청의 표식을 모두 확인한다. Actions는 성공했지만 전파가 느리거나 표식이 다르면 완료로 잘못 표시하지 않고 확인 대기/재확인을 안내한다.

## 매일 자동 공개

cron `0 15 * * *`는 한국 시간 매일 00:00이다. 스케줄 실행은 기본 브랜치의 workflow를 사용하며 GitHub 서비스 부하 때문에 늦어질 수 있다. 공개 저장소에서 60일간 저장소 활동이 없으면 GitHub가 예약 실행을 중지할 수 있으므로 Actions의 활성 상태도 확인한다. 공개 workflow에는 push 트리거가 없다. main 또는 editor-drafts Commit 때 실행되는 `web-check.yml`은 테스트만 하며 공개하지 않는다. 예약 공개는 기기에만 남은 로컬 초고를 업로드할 수 없으므로 사용자가 저장 버튼으로 GitHub에 저장한 최신 원고를 대상으로 한다.

## 로컬 검증

```sh
node --test tests/reader.test.cjs
python -m unittest discover -s tests -p 'test_*.py' -v
python scripts/build_publication.py --reader . --content . --output /tmp/story-publication-check --content-sha <40자리-실제-commit-sha>
```

Windows에서도 같은 Python/Node 명령을 사용할 수 있으며 output은 비어 있는 작업용 폴더를 지정한다. 만들어진 폴더를 정적 HTTP 서버로 열어 기존 URL 해시와 읽기 설정을 확인한다. `file://` 직접 열기는 기존 fetch 로딩 때문에 적합하지 않다.

## 문제 발생 시

실패한 빌드는 공개하지 않으므로 이전 사이트가 유지된다. 공개한 콘텐츠가 잘못됐다면 앱에서 과거 버전을 복원해 새 Commit으로 저장 후 지금 공개하거나, Actions에서 검증된 과거 저장 Commit을 `content_ref`로 지정해 다시 공개한다. Git History는 삭제하지 않는다. 배포 방식 자체를 되돌릴 때는 기록한 기존 Pages publishing source를 복원한다. 저장용 branch를 main으로 병합하면 저장/공개 분리 목적과 달라지므로 콘텐츠 반영을 위해 merge할 필요가 없다.

GitHub 공식 참고: [Pages custom workflows](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages), [scheduled events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).
