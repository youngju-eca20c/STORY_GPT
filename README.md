# 소설 서재 (Story Library)

가볍고 확장 가능한 웹소설 리더입니다. 빌드 도구가 필요 없는 정적 사이트라 GitHub Pages에서 바로 호스팅할 수 있습니다.

## 라이브 데모

https://youngju-eca20c.github.io/STORY_GPT/

## 기능

- 여러 작품을 한 사이트에서 관리
- 라이트/다크 테마 전환
- 글자 크기 조절
- 스크롤/페이지 읽기 모드
- 마지막으로 읽은 회차 저장
- 작품별 설정집, 등장인물, 미래 계획 탭
- 모바일 반응형 UI

## 작품 추가 방법

1. `novels/<작품-id>/` 폴더를 만듭니다.
2. 선택 사항으로 표지 이미지(`cover.jpg`, 800x1200 권장)를 같은 폴더에 넣습니다.
3. 회차 텍스트 파일을 `novels/<작품-id>/chapters/001.txt` 형식으로 추가합니다.
4. `novels/<작품-id>/meta.json`을 작성합니다.

```json
{
  "title": "작품 제목",
  "author": "작가명",
  "description": "줄거리",
  "tags": ["판타지", "코미디"],
  "status": "연재 중",
  "cover": "cover.jpg",
  "chapters": [
    { "id": "001", "title": "1화. 제목", "file": "chapters/001.txt" }
  ]
}
```

5. `data/novels.json`의 `novels` 배열에 작품 정보를 추가합니다.
6. Commit과 Push 후 공개 workflow를 실행하면 GitHub Pages에 반영됩니다.

현재 공개는 `Publish STORY GPT` Actions의 수동 실행 또는 일일 예약 실행으로 진행합니다. `main`은 독자 화면, `editor-drafts`는 저장한 원고를 공급합니다. 자세한 내용은 [공개 설정](docs/PUBLICATION_SETUP.md)을 참고하세요.

`cover` 필드는 작품 폴더 기준의 상대 경로입니다. 생략하면 텍스트 표지가 자동으로 표시됩니다.

## LOST 원고 갱신

`../manuscripts/`의 프롤로그와 1~3화를 아래 명령으로 가져올 수 있습니다.

```sh
python scripts/sync_lost_manuscripts.py --source ../manuscripts --date 2026-10-01
```

회차 제목 앞의 `#`만 리더 형식에 맞게 제거하고 본문은 그대로 가져옵니다. 기존 프롤로그의 회차 ID와 공개 날짜를 유지하며, 가져온 네 회차는 공개로 설정합니다. 다른 회차와 작품 파일은 삭제하지 않습니다. 2026-10-01에는 사용자가 편집한 프롤로그·1~3화 구성에 최신 원고를 반영했습니다. 이전 4~11화는 현재 회차 목록에서 제외되며 Git 이력에 보존되어 있습니다.

표지·등장인물 이미지의 고정된 프레임에는 `object-fit: cover`를 사용합니다. 원본 비율을 유지하며 넘치는 가장자리를 잘라 채우고, 본문 일러스트는 읽기 가능한 원본 비율로 표시합니다.

## 설정집 추가 방법

`novels/<작품-id>/worldbuilding.json` 파일을 만들면 상단의 설정집 버튼에서 접근할 수 있습니다.

```json
{
  "world": [
    { "title": "마왕성", "body": "본문" }
  ],
  "characters": [
    {
      "name": "한이안",
      "role": "주인공",
      "tags": ["인간", "회계사"],
      "body": "본문"
    }
  ]
}
```

`body` 안의 줄바꿈은 화면에 그대로 반영됩니다.

## 구조

```text
.
├── index.html
├── css/style.css
├── js/
│   ├── storage.js
│   ├── views.js
│   └── app.js
├── data/novels.json
└── novels/<id>/
    ├── meta.json
    ├── worldbuilding.json
    └── chapters/*.txt
```

## 로컬에서 보기

브라우저가 `fetch`로 JSON과 텍스트 파일을 불러오기 때문에 `file://` 직접 열기는 동작하지 않을 수 있습니다. 간단한 정적 서버로 확인하세요.

```bash
python -m http.server 8000
```

그 다음 http://localhost:8000 에 접속합니다.
