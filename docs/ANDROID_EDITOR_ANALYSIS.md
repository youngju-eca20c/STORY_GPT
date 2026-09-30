# STORY_GPT 구조 분석과 Android 관리 앱 적용

분석 대상은 `youngju-eca20c/STORY_GPT`의 기존 프로젝트다. 독자 사이트를 새로 만들지 않고 기존 정적 리더에 `android-editor/`를 추가한다.

## 현재 프로젝트 구조

`index.html`이 `css/style.css`, `js/storage.js`, `js/views.js`, `js/app.js`를 로드한다. npm/Vite/React 빌드나 서버/DB가 없는 정적 사이트다. 해시 라우팅은 `#/`, `#/novel/<id>`, `#/read/<novel-id>/<chapter-id>`, `#/worldbuilding[/<id>]`를 지원한다. 스크롤/페이지 읽기, 글꼴/크기/테마와 읽던 회차 저장은 그대로 유지한다. 읽기 설정은 브라우저 localStorage를 사용한다.

콘텐츠와 독자 UI는 이미 잘 분리되어 있다. 코드가 작품 목록을 읽고, 선택한 작품의 메타데이터와 `.txt` 원고를 fetch하여 렌더링한다. 여러 작품을 이미 지원하므로 작품 목록 구조를 다시 만들 필요가 없다.

## 작품과 회차 데이터

`data/novels.json`의 `novels` 배열이 작품 목록이다. 각 항목에 `id`, `title`, `author`, `description`, `tags`, `status`, `cover`가 있다. 작품 세부 정보는 `novels/<id>/meta.json`에서 같은 정보와 `chapters` 배열을 읽는다. 세부 화면은 목록 정보에 메타데이터를 덮어쓴다. 앱의 작품 정보 저장은 두 파일을 하나의 Commit에서 일치시킨다.

분석 시점의 실제 등록 작품:

| ID | 작품명 | 회차 |
| --- | --- | ---: |
| `demon-castle-accounting` | 마왕성 회계팀 신입입니다만 | 9 |
| `last-observatory` | 멸종한 인간을 관측 중입니다 | 11 |

LOST라는 제목의 별도 작품 ID는 현재 목록에 없다. `last-observatory`가 관련 SF 작품이다.

`chapters` 항목은 `id`, `title`, `file`이며, `file`은 `chapters/001.txt` 같은 작품 폴더 기준 경로다. 배열 순서가 목록/이전·다음 회차 순서다. 일부 회차의 `published`와 `updated`는 날짜 문자열이고 공개 여부가 아니다. 원고는 UTF-8 평문이다. 빈 줄로 문단을 나누며 문단 내 줄바꿈을 보존한다. 맨 앞 문단이 회차 제목과 같으면 독자 화면에서 중복 제목을 생략한다. HTML은 실행하지 않고 문자로 표시한다.

`novels/<id>/worldbuilding.json`의 `world`, `characters`, `future` 배열은 설정집 탭이다. 앱을 추가해도 기존 데이터와 설정집 화면은 유지한다.

## 이미지 구조

표지는 `novels/demon-castle-accounting/cover.jpg`, `novels/last-observatory/cover.png`다. `cover`는 작품 폴더 기준 파일명이며 작품 ID/파일명을 URL 인코딩하여 렌더링한다. 기존 이미지 표시 방식과 비율을 유지한다.

기존 일러스트는 루트 `illust/`에 있는 JPEG 7개다. 설정집 등장인물의 `portrait`가 `illust/주인공.jpg`처럼 저장소 루트 기준 경로를 참조하며 각 경로 요소를 URL 인코딩한다. 분석 당시 기존 회차 평문에 일러스트 문법/경로는 없었다.

최소 확장은 아래 두 가지다.

- 선택적 `representative` 필드: 표지와 동일한 작품 폴더 기준 이미지 파일명. 작품 세부 화면에만 표시한다.
- 회차의 독립 문단 `![일러스트](illust/고유파일명.webp)`: 갤러리에서 선택한 이미지를 기존 `illust/`에 저장하고 앱이 자동 삽입한다. JPEG/PNG/WebP만 지원하며 외부 URL, 경로 탈출, URL 인코딩된 경로는 허용하지 않는다. 스크롤 읽기는 본문 위치에, 페이지 읽기는 일러스트 한 장을 한 페이지에 표시한다. 기존 평문은 이전과 동일하게 렌더링한다.

## 배포 분석과 저장/공개 분리

기존 저장소에는 `.github/workflows`가 없었다. README는 Commit/push 후 Pages에 반영된다고 안내한다. GitHub의 기존 `dynamic/pages/pages-build-deployment` 성공 실행 `36246822745`와 main의 `9787a…` 회차 추가 Commit을 대조하여, main 변경이 현재 독자 사이트에 자동 배포되는 실효 동작을 확인했다. 인증된 GitHub Pages 설정 조회에서도 build_type=legacy, source.branch=main, source.path=/를 확인했다. 따라서 main 루트의 변경이 자동 배포되는 기존 방식이다. Android 앱은 main에 원고를 직접 저장하지 않으며, 이 구현을 활성화할 때 Pages Source를 GitHub Actions로 전환한다.

가장 단순한 분리는 같은 저장소 안의 `editor-drafts` 저장용 브랜치다. Android 앱은 이 브랜치에 콘텐츠를 Commit한다. 독자 UI/배포 스크립트는 안정된 `main`을 사용한다. `publish-story.yml`은 저장용 브랜치의 정확한 Commit을 한 번 확정한 뒤 공개 파일만 산출물로 구성하여 기존 GitHub Pages 주소로 배포한다. 저장용 브랜치의 UI/스크립트를 실행하지 않는다.

트리 전체 복사 대신 공개 작품/회차의 참조 폐쇄를 복사한다. `visibility`가 없으면 기존 데이터와 호환되도록 공개, `public`은 공개, `private`/`draft`는 제외다. 숨긴 작품/회차의 메타데이터·본문, 삭제 후 남은 파일, 참조되지 않는 이미지, Android 프로젝트와 개발 자료는 Pages 산출물에 들어가지 않는다. 공개 저장소의 GitHub 코드와 Git History에서는 원고를 볼 수 있으므로 이 공개 상태는 독자 사이트 노출 제어이며 저장소 접근 제한은 아니다.

매일 한국 시간 00:00은 UTC cron `0 15 * * *`로 설정한다. GitHub 스케줄은 지연될 수 있어 정확한 초 단위 공개를 보장하지 않는다. 앱의 지금 공개는 저장된 정확한 Commit SHA와 요청 ID를 전달하며 배포 완료와 `publication.json`의 동일 SHA/요청 ID를 확인한다. 자세한 활성화 절차는 `PUBLICATION_SETUP.md`를 따른다.

## Android 앱이 수정하는 파일

작품 생성/수정/삭제: `data/novels.json`, `novels/<id>/meta.json`와 해당 작품 파일. 회차 생성/수정/삭제/순서/공개 상태: `meta.json`의 `chapters`, `novels/<id>/chapters/*.txt`. 표지/대표 이미지: `novels/<id>/`의 이미지와 두 메타데이터 참조. 회차 일러스트: 기존 `illust/`의 고유 이미지 파일과 원고의 독립 이미지 문단. 기존 설정집과 임의 파일은 앱 작업 때문에 일괄 삭제하지 않는다.

## 구현 방법

`android-editor/`의 Kotlin/Jetpack Compose 앱이 GitHub REST API를 직접 사용한다. 사용자명/저장소/브랜치/PAT는 처음 설정하며 PAT를 소스에 넣지 않는다. 작성 중 원고와 이미지 선택은 기기 파일에 자동 저장하고 저장 버튼에서만 Git tree/Commit/ref API로 관련 변경을 한 Commit에 반영한다. 새 브랜치는 main 스냅샷에서 생성하되 main에 원고를 저장하지 않는다. 원격 Commit이 변경되면 덮어쓰기 전에 충돌을 알린다.

미리보기는 같은 `views.js`/`style.css`를 Android WebView 자산으로 포함하고 `Views.renderReader`를 호출한다. 앱의 로컬 이미지 선택과 원격 이미지 경로를 미리보기에서 해결하여 실제 독자 화면의 스크롤 모양을 재사용한다. 신규 서버/데이터베이스/로그인 시스템은 필요하지 않다. 설치용 APK를 빌드하고 직접 설치한다.
