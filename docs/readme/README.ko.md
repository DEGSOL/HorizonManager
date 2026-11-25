# Hovq 0.6.4

[Русский](../../README.md) · [English](README.en.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Español](README.es.md) · [日本語](README.ja.md) · **한국어** · [简体中文](README.zh.md) · [Қазақша](README.kk.md) · [Татарча](README.tt.md)

Hovq는 자신의 VPS에 [Horizon](https://github.com/Thysrael/Horizon)을 설치하고 관리하며 뉴스 다이제스트를 읽을 수 있는 Android 앱입니다. Horizon은 서버에서 기사를 수집하고 중복을 제거한 뒤 선택한 AI 모델로 평가와 번역을 수행하고 다이제스트를 작성합니다.

[서명된 APK 다운로드 및 릴리스 노트 보기](https://github.com/DEGSOL/HorizonManager/releases/latest)

## 주요 기능

- RSS, Telegram, Reddit, Hacker News 소스, 평가 프로필, 표지 이미지, 요청 시 기사 보강.
- 수동 실행, 요일별 실행, 또는 지정한 시작 시각부터 일정한 간격으로 실행. 예약은 VPS에서 동작합니다.
- 검색, 필터와 읽지 않은 기사를 표시하는 피드, 즐겨찾기, 보관함, 다운로드한 다이제스트의 오프라인 읽기.
- 활동 로그와 휴대전화 및 서버 설정의 암호화 백업.

## 설치 및 첫 다이제스트

**설치에는 Ubuntu Server 24.04 LTS 또는 Debian 12를 실행하고 SSH로 접속할 수 있는 VPS만 있으면 됩니다.** Hovq에서 연결하고 설치를 확인하면 앱이 Horizon, 에이전트, 필요한 종속성을 자동으로 설치합니다.

1. Android 8.0 이상에서 APK를 설치합니다. Ubuntu Server 24.04 LTS 또는 Debian 12, systemd, SSH를 지원하고 OS 저장소, GitHub, PyPI에 접속 가능한 VPS를 준비합니다. 설치 프로그램은 아키텍처, 최소 432 MiB의 RAM과 1.5 GiB의 여유 공간을 확인합니다. 수집과 처리에는 더 많은 리소스가 필요할 수 있습니다.
2. Hovq에 서버를 추가하고 SSH 비밀번호나 개인 키를 지정한 뒤 공급업체 콘솔에서 지문을 확인합니다. SSH에서 VPS의 `127.0.0.1:18777`로 포워딩할 수 있어야 합니다. 설치를 확인하고 필요하면 sudo 비밀번호를 입력합니다. 기존 에이전트에도 연결할 수 있습니다.
3. AI 설정에서 공급업체, 모델, API 키를 지정합니다. OpenRouter와 UnoRouter의 모델 목록은 앱에서 불러올 수 있습니다. 소스를 추가하고 처리 프로필을 선택한 다음 수집을 시작합니다.
4. 피드에서 다이제스트를 엽니다. 뉴스 설정에서 일정과 다이제스트 언어를 선택할 수 있습니다. 인터페이스와 다이제스트는 위에 나열된 모든 언어를 지원합니다.

## 알림 및 업데이트

**설정 → 인터페이스 → 알림**에서 새 다이제스트 알림을 켜고 Android에서 허용합니다. Hovq는 SSH 또는 설정된 HTTPS로 VPS와 직접 연결을 유지합니다. 모니터링 중에는 Android에서 무음 서비스 알림이 표시되고, 다이제스트가 준비되면 별도 알림이 도착합니다. 연결이 끊기면 다시 연결해 새 발행물이 있는지 확인합니다.

동일한 인증서로 서명된 Hovq 0.4.5–0.6.3 위에 APK를 설치할 수 있습니다. 0.4.4 이하에서는 새로 설치해야 합니다. 기존 VPS는 Hovq에서 에이전트를 0.1.21로 업데이트합니다. 요청 시 보강 모드에서도 표현이 다른 기사가 같은 사건을 다루는지 확인합니다. 각 프로필의 주제 중복 제거 설정은 계속 적용됩니다.

## 소스에서 빌드

JDK 17 또는 21, Android SDK 36 및 Build Tools 36.0.0을 사용합니다. `ANDROID_HOME` 또는 `android/local.properties`에 SDK 위치를 설정합니다.

```bash
cd android
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

서명된 APK를 만들려면 다음 환경 변수를 설정합니다

`HM_RELEASE_KEYSTORE`, `HM_RELEASE_KEY_ALIAS`, `HM_RELEASE_STORE_PASSWORD`, `HM_RELEASE_KEY_PASSWORD`

[이 릴리스의 앱 및 에이전트 소스](https://github.com/DEGSOL/HorizonManager/tree/v0.6.4) · [아키텍처 및 API](https://github.com/DEGSOL/HorizonManager/blob/v0.6.4/docs/ARCHITECTURE.md) · [테스트](https://github.com/DEGSOL/HorizonManager/blob/v0.6.4/docs/TESTING.md)
