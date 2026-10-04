# choifile

Android 파일 관리자. 탭·그리드·드래그 선택 같은 기본 탐색 위에 지문 잠금, 폴더 보호, 숨긴 항목, 파일 암호화, 네트워크 저장소, 내장 뷰어를 한 앱에 담았습니다.

## 기능

- **탐색**: 여러 탭(좌우 스와이프 전환), 그리드·목록, 폴더별 정렬과 스크롤 위치 기억, 드래그 범위 선택, 즐겨찾기 드로어
- **파일 작업**: 복사·이동·이름 변경·공유, 휴지통과 작업 기록 되돌리기, 파일 생성기, `.nomedia` 토글, APK 설치
- **보안**: 지문·기기 잠금, 폴더 보호와 유예 시간, 숨긴 항목 보관함, AES-256-GCM 파일 암호화(.cfenc), 덮어쓰기 삭제
- **압축**: zip·7z·tar 계열 만들기와 풀기, rar 읽기, 압축 파일을 폴더처럼 탐색
- **네트워크**: SFTP·SMB·FTP·WebDAV, Tailscale 기기 연결, PC에서 접속하는 내장 HTTP·FTP 서버(토큰 인증)
- **뷰어**: 이미지(좌우 넘김), 동영상(실시간 탐색, 슬로 재생, FFmpeg 코덱, 자막), 텍스트·코드, PDF, Markdown, 만화(세로 스크롤, 읽던 위치 기억)
- **도구**: 검색 인덱스, 저장 공간 분석, 중복 파일 찾기, 해시 검사, 자동화 규칙

요구 사항: Android 8.0(API 26) 이상. 전체 파일 접근 권한을 사용합니다.

## 빌드

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug testDebugUnitTest
```

릴리스 서명 정보는 `~/.gradle/gradle.properties`의 `CHOIDEV_RELEASE_*` 값에서 읽습니다. 값이 없으면 서명하지 않은 릴리스가 만들어집니다. `-Pchoifile.abi=arm64-v8a`로 한 가지 ABI만 담은 APK를 만들 수 있습니다.

## 라이선스

GPL-3.0. 동영상 코덱용 FFmpeg 확장(NextLib)이 GPL-3.0이라 앱 전체를 같은 라이선스로 공개합니다. 사용한 라이브러리는 [THIRD_PARTY.md](THIRD_PARTY.md)에 있습니다.
