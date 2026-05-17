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
6. commit과 push를 하면 GitHub Pages에 반영됩니다.

`cover` 필드는 작품 폴더 기준의 상대 경로입니다. 생략하면 텍스트 표지가 자동으로 표시됩니다.

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
