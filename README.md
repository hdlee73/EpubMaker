# Epubmaker (안드로이드)

PDF에서 텍스트를 추출해 **글자 크기·줄 간격·여백을 조절한 EPUB 전자책**으로 만들어 주는 안드로이드 앱입니다.

- 버전: 1.0.0
- 만든이: 이현덕 (hdlee73@gmail.com)
- 저장소: https://github.com/hdlee73/EpubMaker

## 기능

- PDF 텍스트 추출: 머리글/바닥글·쪽번호 제거, 끊긴 문단 이어 붙이기, 영문 하이픈 줄바꿈 복원, 제목 감지(목차 생성)
- **표는 이미지로 캡처**해서 삽입(원본 모양 유지), 그림도 이미지로 삽입
- 읽기 서식 지정: 글자 크기, 줄 간격, 좌우 여백, 글꼴, 정렬, 문단 간격/들여쓰기 (미리보기 제공)
- 변환 결과를 바로 열거나 공유
- **뒤로가기 버튼/제스처는 앱을 끄지 않고 이전 화면으로 이동** (홈 화면에서만 앱 종료)
- 정보 화면(앱 이름·버전·만든이·업데이트 내역)과 자동 업데이트 확인 (서명 검증)

> EPUB 리더 앱에서도 글자 크기·줄 간격·여백을 다시 바꿀 수 있습니다. 앱에서 정하는 값은 기본값입니다.

## 한계

- 스캔(이미지) PDF는 텍스트가 없어 글자를 읽을 수 없습니다(OCR 필요). 앱이 경고를 표시합니다.
- 표 인식은 "가로로 간격을 두고 정렬된 짧은 칸이 3줄 이상 이어지는 경우"를 표로 봅니다. 칸 안 글이 길거나 표가 복잡하면 일반 텍스트로 나올 수 있습니다.
- 2단 편집, 각주, 수식은 읽는 순서가 어긋날 수 있습니다.
- 회전된 페이지의 그림은 추출하지 않습니다.

## 빌드

- Android Studio(최신)에서 열어 실행하거나, 터미널에서 `./gradlew assembleDebug`
- GitHub에 push 하면 Actions(`Build`)가 단위 테스트와 디버그 APK 빌드를 자동으로 합니다.
- 요구 사항: JDK 17, Android SDK 35, minSdk 26 (Android 8.0 이상)

## 공개 저장소 보호와 자동 업데이트

소스가 공개되어 있어도 다른 사람이 **공식 업데이트를 만들어 배포할 수 없도록** 3중으로 막습니다.

1. **APK 서명 키(키스토어)는 GitHub Secrets에만** 둡니다. 안드로이드는 같은 키로 서명된 APK만 기존 앱을 업데이트하게 합니다.
2. **업데이트 서명(.sig)**: 앱은 공식 저장소의 릴리스에서만 받고, 앱에 내장된 공개키로 서명이 검증된 파일만 설치 화면을 엽니다. 비공개키는 GitHub Secrets(`UPDATE_SIGNING_KEY`)에만 있습니다.
3. **라이선스**: 수정·재배포·업데이트 버전 배포를 금지합니다 ([LICENSE](LICENSE)).

### 최초 1회 설정 (저장소 소유자)

**A. APK 서명용 키스토어 만들기** (JDK의 `keytool` 사용, 파일은 안전한 곳에 백업)

```bash
keytool -genkeypair -v -keystore epubmaker-release.jks -alias epubmaker -keyalg RSA -keysize 2048 -validity 10000
```

Base64로 변환해 복사합니다.
- Windows(PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("epubmaker-release.jks")) | Set-Clipboard`
- macOS/Linux: `base64 -w0 epubmaker-release.jks`

**B. 업데이트 서명 키 만들기**

```bash
pip install cryptography
python tools/gen_keys.py
```

- `PUBLIC` 값 → `app/src/main/java/com/hdlee/epubmaker/update/Updater.kt`의 `PUBLIC_KEY_B64`에 붙여 넣고 커밋 (공개되어도 안전)
- `PRIVATE` 값 → 아래 Secret `UPDATE_SIGNING_KEY`에 등록 (그 외 어디에도 저장하지 않음)

**C. GitHub → Settings → Secrets and variables → Actions 에 5개 등록**

| 이름 | 값 |
|---|---|
| `KEYSTORE_BASE64` | A에서 만든 키스토어의 Base64 |
| `KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `KEY_ALIAS` | `epubmaker` (A에서 지정한 별칭) |
| `KEY_PASSWORD` | 키 비밀번호 |
| `UPDATE_SIGNING_KEY` | B의 PRIVATE 값 전체 (`-----BEGIN` ~ `END-----` 포함) |

**D. 브랜치 보호**: `main` 브랜치 보호(직접 push 제한, PR 승인 필수)와 외부 기여자 워크플로 승인 필수를 켭니다.

> 키스토어를 잃어버리면 같은 앱으로 업데이트를 낼 수 없습니다. 키스토어 파일과 비밀번호는 꼭 백업하세요.

### 새 버전 배포

1. `CHANGELOG.md`와 `Models.kt`의 `Changelog`에 새 버전 내용을 추가합니다.
2. 태그를 push 합니다: `git tag v1.0.1 && git push origin v1.0.1`
3. GitHub Actions(`Release`)가 서명된 APK와 `.sig`를 릴리스에 올립니다.
4. 사용자의 앱은 다음 실행 때(또는 '업데이트 확인') 새 버전을 안내하고, 서명을 검증한 뒤 설치 화면을 엽니다.

공개키가 비어 있는 동안에는 자동 설치가 꺼져 있고, 새 버전 안내 시 릴리스 페이지만 열립니다.
`Build` 워크플로가 만드는 디버그 APK는 테스트용이며, 배포용 APK와 서명 키가 달라 서로 덮어 설치할 수 없습니다.

## 라이선스

All rights reserved. 수정·재배포·업데이트 버전 배포 금지 ([LICENSE](LICENSE)).
PDF 처리에 [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)(Apache-2.0)를 사용합니다.
