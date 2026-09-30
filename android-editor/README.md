# STORY GPT Editor

기존 STORY_GPT 독자용 사이트를 관리하는 개인용 Android 앱입니다. Kotlin + Jetpack Compose로 작성했으며 Android 8.0(API 26) 이상에 APK를 직접 설치합니다. 별도 서버, 데이터베이스, 회원가입은 사용하지 않습니다.

## 설치

빌드한 `releases/STORY-GPT-Editor.apk`를 휴대전화로 복사하여 실행합니다. Android에서 해당 파일을 연 앱의 **알 수 없는 앱 설치 허용**을 켜면 설치할 수 있습니다. Google Play 등록은 필요 없습니다.

이 APK는 개인용 debug 서명입니다. 이후 같은 빌드 환경과 `.local-signing/debug.keystore`를 유지하면 기존 앱 위에 업데이트할 수 있습니다. 이 키는 Git에 포함되지 않으므로 프로젝트를 옮길 때 별도로 보관하세요. GitHub Actions의 새 실행에서 만든 APK는 서명이 달라질 수 있습니다. 다른 서명의 APK로 바꾸려면 앱을 삭제해야 하며 앱에 남은 임시 원고와 설정도 지워지므로 먼저 GitHub에 저장하세요.

## 최초 연결

설정에서 다음 값을 입력하고 **GitHub 연결 테스트**를 실행합니다.

| 항목 | 기본값 / 입력값 |
| --- | --- |
| GitHub 사용자명 | `youngju-eca20c` |
| Repository | `STORY_GPT` |
| 저장 Branch | `editor-drafts` |
| GitHub Token | 본인이 생성한 Personal Access Token |

Fine-grained Personal Access Token은 대상 Repository를 `STORY_GPT`로 한정하고 **Contents: Read and write**, **Actions: Read and write** 권한을 설정하세요. Token은 앱 기기의 로컬 설정에 저장하며 소스와 APK에는 포함하지 않습니다. Token 만료 시 설정에서 새 Token으로 바꾸면 됩니다. Android 백업과 기기 이전에서 앱 데이터가 제외됩니다.

앱의 콘텐츠 저장은 `editor-drafts` 브랜치를 사용합니다. `main`은 독자용 사이트와 배포 워크플로를 관리하기 때문에 앱의 저장 브랜치로 사용할 수 없습니다. 연결 시 준비된 원고 브랜치를 읽고, 없는 경우 현재 저장소의 공개 원고를 기준으로 준비합니다.

## 작성과 공개

1. 작품을 선택하거나 **새 작품**을 만듭니다.
2. 작품 정보, 표지, 회차 본문을 수정합니다. 회차 편집 화면에서 작성 중인 원고는 기기에 자동저장됩니다.
3. **저장**을 누르면 원고와 변경한 이미지가 GitHub에 하나의 커밋으로 저장됩니다.
4. **지금 공개**는 저장된 원고를 대상으로 `publish-story.yml`을 실행하고 완료 상태를 확인합니다.
5. 수동 공개하지 않은 저장 원고도 매일 한국시간 00:00 예약 배포 대상이 됩니다. GitHub Actions 예약 작업은 플랫폼 사정으로 지연될 수 있습니다.

자동저장은 GitHub 저장과 별개입니다. GitHub 저장 전 강제 종료한 원고는 다음 실행에서 로컬 임시 원고로 복구할 수 있습니다. 이미지도 앱 저장소에 먼저 복사하므로 갤러리 선택 후 앱을 다시 실행할 수 있습니다.

공개/비공개는 독자용 GitHub Pages에 표시할지 결정합니다. 공개 GitHub 저장소에 저장된 비공개 원고와 이미지까지 외부에서 비밀로 보관하는 기능은 아닙니다.

## 기존 사이트 배포 준비

원고 브랜치를 사용하는 코드와 `.github/workflows/publish-story.yml`이 기본 브랜치에 올라간 후 GitHub Repository의 **Settings → Pages → Build and deployment → Source → GitHub Actions**로 한 번 변경합니다. 그때까지 기존 Pages 설정을 변경하지 않아도 기존 사이트는 유지됩니다.

`publish-story.yml`은 앱의 `content_ref`, `content_branch`, `request_id` 입력을 받아 저장한 정확한 커밋을 빌드합니다. 비공개 작품과 회차는 공개 산출물에서 제외합니다. Android 소스, Token, 로컬 임시 원고를 Pages에 배포하지 않습니다.

워크플로 YAML을 Personal Access Token으로 Git push할 때는 별도의 **Workflows: Read and write** 권한이 필요할 수 있습니다. 앱의 일반 원고 편집에는 이 권한이 필요하지 않습니다.

## 개발 환경과 APK 빌드

- JDK 17 또는 21
- Android SDK Platform 36 / Build Tools 35.0.0
- Gradle 8.13 (프로젝트 wrapper 사용)
- Android Gradle Plugin 8.13.0
- Kotlin / Compose compiler 2.2.20
- Jetpack Compose BOM 2025.10.01

Android Studio에서 이 `android-editor` 폴더를 열거나, SDK/JDK 환경이 있는 터미널에서 실행합니다.

```powershell
.\build-apk.ps1 -SdkPath 'C:\Android\Sdk' -JavaPath 'C:\Java\jdk-17'
```

스크립트는 APK 빌드, JVM 단위 테스트, Android Lint를 실행한 후 `releases/STORY-GPT-Editor.apk`에 설치 파일을 복사하고 SHA-256을 출력합니다. 기존 Gradle 캐시를 지정하려면 `-GradleCache`를 사용합니다. 검사를 이미 마친 뒤 재빌드만 할 때는 `-SkipChecks`를 사용할 수 있습니다.

Linux/macOS:

```sh
chmod +x gradlew
./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
```

직접 빌드한 기본 산출물은 `app/build/outputs/apk/debug/app-debug.apk`입니다. `.github/workflows/build-android.yml`을 수동 실행해도 **STORY-GPT-Editor-APK** artifact로 APK를 받을 수 있습니다. 해당 워크플로는 Pages를 공개하지 않습니다.

## 원본 사이트와의 연결

앱은 기존 `data/novels.json`, `novels/<id>/meta.json`, `novels/<id>/chapters/*.txt` 형식을 사용합니다. 표지와 대표 이미지는 기존 작품 폴더에, 회차 일러스트는 기존 루트 illust/ 폴더에 저장합니다. 앱이 본문에 삽입 문법을 자동으로 작성합니다. 미리보기는 빌드 때 원본 `css/style.css`와 `js/views.js`를 assets로 복사하여 사용합니다. 앱을 수정할 때도 별도의 독자용 사이트를 생성할 필요가 없습니다.

기존 작품의 설정집과 앱에서 편집하지 않는 추가 JSON 필드는 그대로 보존합니다. 삭제 및 버전 복원은 GitHub에 새 커밋으로 기록하므로 Git History를 유지합니다.
