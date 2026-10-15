# choifile

**iOS 스타일의 Android 파일 관리자. 뷰어도 앱 안에 다 들어 있어요.** 탭, 그리드·목록, 드래그 선택, 폴더별 정렬과 필터 위에
지문 잠금, 폴더 보호, 숨긴 항목 보관함, 파일 암호화, 네트워크 저장소, 동영상·음악·책 플레이어를 한 앱에 담았습니다.

[English](README.md) · 한국어 · 앱 언어: English, 한국어, 日本語

<p>
  <img src="docs/screenshots/browser.png" width="24%" alt="큰 제목이 있는 파일 목록" />
  <img src="docs/screenshots/grid.png" width="24%" alt="필터를 켠 그리드 보기" />
  <img src="docs/screenshots/menu.png" width="24%" alt="컨텍스트 메뉴" />
  <img src="docs/screenshots/dark.png" width="24%" alt="다크 모드" />
</p>
<p>
  <img src="docs/screenshots/video.png" width="49%" alt="자막이 나오는 동영상 플레이어" />
  <img src="docs/screenshots/music.png" width="24%" alt="음악 플레이어" />
  <img src="docs/screenshots/ebook.png" width="24%" alt="전자책 리더" />
</p>

## 내려받기

[Releases](https://github.com/choidevhi/choifile/releases/latest)에서 APK를 받으세요.

- `choifile-x.y.z-arm64.apk`: 최근 몇 년 사이의 거의 모든 폰용(파일이 더 작음)
- `choifile-x.y.z.apk`: 모든 기기용(arm64, armv7, x86, x86_64)

Android 8.0(API 26) 이상이 필요합니다. 저장 공간을 관리하려고 전체 파일 접근 권한을 사용합니다.

## 기능

- **탐색**: iOS식 큰 제목과 상위 폴더 이름이 붙은 뒤로 버튼, 폴더 이동 애니메이션, 가장자리 밀어 뒤로 가기, 여러 탭, 폴더마다 기억하는 그리드·목록과 스크롤 위치
- **정렬**: 이름(자연 정렬)·날짜·크기·종류, 폴더 먼저 또는 섞기, 모든 폴더에 같은 정렬 또는 폴더마다 따로
- **필터**: 이름(와일드카드 `*` `?`, 초성 검색 ㅎㄱ), 종류(폴더·사진·동영상·음악·문서·압축·APK), 수정한 날짜, 크기
- **파일 작업**: 복사·이동·이름 변경·공유·압축, 휴지통과 되돌리기, zip·7z·tar 만들기와 풀기, 압축 파일을 폴더처럼 탐색, rar 읽기, `.nomedia`, APK 설치, 중복 파일 찾기, 저장 공간 분석, 검색 인덱스, 자동화 규칙
- **보안**: 지문·기기 잠금, 폴더 보호와 유예 시간, 숨긴 항목 보관함, AES-256-GCM 파일 암호화(.cfenc), 덮어쓰기 삭제
- **네트워크**: SFTP·SMB·FTP·WebDAV, Tailscale 기기 연결, PC에서 접속하는 내장 HTTP·FTP 서버(토큰 인증)
- **동영상**: 제스처(탐색, 밝기, 볼륨 200%, 2배속, 핀치 줌), 화면 비율, 잠금, 이어보기, 폴더 재생목록, A-B 반복, 자막(SRT·ASS·VTT·SMI, 인코딩 자동 감지, 0.1초 싱크), PiP·소리만 재생·멈춤, 하드웨어·FFmpeg 디코더, 슬립 타이머, 스크린샷
- **음악**: 파일을 둘러보거나 앱을 나가도 계속 재생, 알림·잠금화면 제어, 앨범 아트와 태그, 폴더 재생목록, 임의 재생, 반복, 속도, 슬립 타이머, 미니 플레이어
- **책과 텍스트**: `.txt` 소설용 전자책 리더(책장 넘김, 글꼴, 테마, 목차, 검색, 책갈피, 볼륨 키), 크기에 상관없이 바로 열리는 큰 텍스트, 원래 인코딩을 지키는 텍스트 편집
- **그 밖의 뷰어**: 사진(넘기기, 확대), PDF, Markdown, 코드 강조, 만화(세로 스크롤, 읽던 위치 기억)

## 빌드

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug testDebugUnitTest
```

릴리스 서명 정보는 `~/.gradle/gradle.properties`의 `CHOIDEV_RELEASE_*` 값에서 읽습니다. 값이 없으면 서명하지 않은 릴리스가 만들어집니다. `-Pchoifile.abi=arm64-v8a`로 한 가지 ABI만 담은 APK를 만들 수 있습니다.

## 라이선스

GPL-3.0. 동영상 코덱용 FFmpeg 확장(NextLib)이 GPL-3.0이라 앱 전체를 같은 라이선스로 공개합니다. 사용한 라이브러리는 [THIRD_PARTY.md](THIRD_PARTY.md)에 있습니다.
