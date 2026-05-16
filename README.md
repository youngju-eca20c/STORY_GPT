# 소설 서재 (Story Library)

깔끔하고 확장 가능한 웹소설 리더. 정적 사이트로 GitHub Pages에서 호스팅.

## 라이브 데모

- https://youngju-eca20c.github.io/STORY_CLODE/

## 기능

- 📚 여러 작품을 한 사이트에서 관리
- 🌗 라이트/다크 테마 전환 (선호도 저장)
- 🔤 글자 크기 조절 (14–28px)
- 📍 마지막으로 읽은 회차 자동 기억 ("이어 읽기")
- ← → 이전/다음 화 빠른 이동
- 📱 모바일 반응형
- ⚡ 빌드 도구·프레임워크 없는 순수 정적 사이트

## 새 작품 추가하는 법

1. `novels/<작품-id>/` 폴더 생성
2. (선택) 표지 이미지를 같은 폴더에 추가 (예: `cover.jpg`, 800×1200 권장)
3. 챕터 텍스트 파일을 `novels/<작품-id>/chapters/001.txt` 같은 형태로 추가
   - 문단은 빈 줄로 구분
4. `novels/<작품-id>/meta.json` 작성:
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
5. `data/novels.json`의 `novels` 배열에 같은 정보를 한 줄 추가 (cover 포함 가능)
6. commit & push — GitHub Pages가 자동으로 반영

> `cover` 필드는 novel 폴더 기준 상대 경로입니다. 생략 시 텍스트 표지가 자동으로 표시됩니다.

## 새 회차 추가하는 법

해당 작품의 `chapters/` 폴더에 텍스트 파일을 두고, `meta.json`의 `chapters` 배열에 한 줄 추가하면 끝.

## 구조

```
.
├── index.html              # 단일 진입점 (해시 라우터)
├── css/style.css           # 테마·반응형 스타일
├── js/
│   ├── storage.js          # localStorage 래퍼
│   ├── views.js            # 뷰 렌더링
│   └── app.js              # 라우터 + 데이터 로딩
├── data/novels.json        # 전체 작품 인덱스
└── novels/<id>/
    ├── meta.json           # 작품·회차 메타
    └── chapters/*.txt      # 본문
```

## 로컬에서 보기

`fetch`로 JSON과 텍스트를 불러오기 때문에 `file://` 직접 열기는 동작하지 않음. 간단한 정적 서버가 필요:

```bash
# Python
python -m http.server 8000

# Node
npx serve .
```

그 후 http://localhost:8000 접속.
