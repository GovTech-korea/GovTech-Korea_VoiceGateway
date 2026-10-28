# GovTech-Korea VoiceGateway

GovTech Korea 공모전 출품작 **모민(MOMIN, 모두의 민원)**의 음성 연계 게이트웨이입니다.
민원인 앱과 상담원 앱의 통화 음성을 WebSocket으로 중계하면서, 민원인 음성을 실시간으로 STT·위험 탐지로 넘깁니다.

- **앱 개발자용 연동 규격: [docs/app-integration.md](docs/app-integration.md)**

## 필요한 것

| 항목 | 버전 | 비고 |
|---|---|---|
| JDK | 25 | 기본 JDK가 다른 버전이어도 됩니다 (아래 "JDK 25 설정" 참고) |
| Git | - | |

Gradle은 따로 설치하지 않아도 됩니다. 저장소에 포함된 Gradle Wrapper(`./gradlew`)를 사용합니다.

## 내려받기

```bash
git clone https://github.com/GovTech-korea/GovTech-Korea_VoiceGateway.git
cd GovTech-Korea_VoiceGateway
git switch develop
```

개발 중인 최신 코드는 `develop` 브랜치에 있습니다.

## JDK 25 설정

빌드는 Gradle toolchain으로 **JDK 25**를 사용합니다. 설치된 JDK 25를 Gradle이 찾지 못하면 빌드가 실패합니다.

1. JDK 25 설치 (macOS Homebrew 예시)

   ```bash
   brew install openjdk@25
   ```

2. Gradle이 찾는지 확인

   ```bash
   ./gradlew -q javaToolchains
   ```

   목록에 `JDK 25`가 보이면 됩니다.

3. 보이지 않으면 `~/.gradle/gradle.properties`에 JDK 25 경로를 적습니다.

   ```properties
   # Homebrew (Apple Silicon) 예시
   org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
   ```

## 실행

```bash
./gradlew bootRun
```

`http://localhost:8080`에서 실행됩니다. 루트 경로(`/`)는 404를 반환합니다.

STT로 보낸 음성을 파일로 남기려면 기록 폴더를 지정해 실행합니다.

```bash
./gradlew bootRun --args='--voicegateway.stt.dump-dir=build/stt-dump'
```

## 테스트 화면 (앱 없이 시험하기)

실행한 뒤 Chrome에서 **http://localhost:8080/test** 를 열면 역할 선택 화면이 나옵니다.

| 화면 | 주소 | 용도 |
|---|---|---|
| 상담원 화면 | `/test/agent.html` | 접속 → 벨이 울리면 받기 → 통화 중 위험 구간 차단(단축키 M) |
| 민원인 화면 | `/test/citizen.html` | 마이크 / 테스트 톤 / 오디오 파일로 전화 걸기, 대기 순번·통화 시간 |
| 통합 콘솔 | `/test/console.html` | 한 화면에서 두 역할을 모두 시험, 대기열·통화·STT 전달 현황, STT 기록 듣기 |

1. 한 사람(또는 창)이 **상담원 화면**에서 `접속` → "대기 중"
2. 다른 사람이 **민원인 화면**에서 `전화 걸기`
3. 상담원 화면에 벨이 울리면 `받기` (20초 안에 안 받으면 다음 상담원에게 넘어가고, 안 받은 상담원은 "자리 비움")
4. 민원인 목소리는 상담원에게 약 0.8초 늦게 들립니다. 상담원이 `위험 구간 차단`을 누르면 아직 들리지 않은 부분부터 **다시 누를 때까지** 계속 무음이 됩니다 (통합 콘솔에는 한 번만 지우는 버튼도 있음)

- 한 기기에서 두 역할을 같이 쓰거나 스피커로 들을 때는 **이어폰**을 씁니다. 스피커 소리가 마이크로 다시 들어갑니다.
- 마이크는 `localhost`나 HTTPS에서만 쓸 수 있습니다. 다른 기기에서 IP로 접속하면 테스트 톤·오디오 파일을 쓰거나 아래 터널을 씁니다.
- `dump-dir`을 지정했으면 통합 콘솔의 "STT로 보낸 음성 듣기"에서 STT가 받은 음성을 그대로 들을 수 있습니다.

### 팀원이 원격으로 들어와 시험하기 (임시 공개 주소)

```bash
brew install cloudflared
cloudflared tunnel --url http://localhost:8080
```

출력된 `https://xxxx.trycloudflare.com/test` 를 팀원에게 보내면 됩니다. HTTPS라서 팀원 기기에서도 마이크를 쓸 수 있습니다.

- 터널을 다시 켜면 주소가 바뀝니다 (게이트웨이만 재시작하면 그대로)
- 시험하는 동안 Mac이 잠들지 않게 하고, 끝나면 터널을 끕니다. 인증이 없으니 주소는 팀원에게만 보냅니다

## 빌드와 테스트

```bash
./gradlew build      # 컴파일 + 전체 테스트 + 커버리지 리포트
./gradlew test       # 테스트만
```

| 결과물 | 위치 |
|---|---|
| 테스트 리포트 | `build/reports/tests/test/index.html` |
| 커버리지 리포트 (JaCoCo) | `build/reports/jacoco/test/html/index.html` |
| 실행 jar | `build/libs/` |

## 설정

- 설정 파일: `src/main/resources/application.yaml`
- 비밀번호, API 키 같은 비밀값은 저장소에 커밋하지 않고 환경변수로 주입합니다.

| 설정 | 기본값 | 설명 |
|---|---|---|
| `voicegateway.delay-ms` | `800` | 민원인 → 상담원 음성 지연 (ms). 0이면 지연 없음 |
| `voicegateway.ring-timeout-ms` | `20000` | 상담원이 벨을 받지 않으면 다음 상담원에게 넘기는 시간 (ms) |
| `voicegateway.stt.format` | `PCM16_16K` | STT로 보내는 형식. `PCM16_16K`(받은 그대로) / `PCM16_8K`(8kHz로 다운샘플) |
| `voicegateway.stt.dump-dir` | 없음 | 지정하면 STT로 보낸 음성을 `<callId>-<시작ms>.pcm`으로 기록 |

| HTTP API | 설명 |
|---|---|
| `POST /api/v1/calls/{callId}/mute` | 아직 상담원에게 나가지 않은 민원인 음성을 무음 처리. 본문 `{"fromEpochMs","toEpochMs"}`로 구간 지정 가능 (생략 시 전체) |
| `PUT /api/v1/calls/{callId}/mute-hold` | 차단 유지 켜기/끄기. 본문 `{"hold": true}`면 아직 나가지 않은 음성과 이후 들어오는 음성을 계속 무음 처리, `{"hold": false}`면 해제 (STT에는 원본 그대로) |
| `GET /api/v1/status` | 설정, 상담원·대기열·통화·STT 전달 현황 |
| `GET /api/v1/stt/recordings` | STT 기록 목록 (`dump-dir` 지정 시) |
| `GET /api/v1/stt/recordings/{name}` | STT 기록을 WAV로 받기 |

> 아직 인증이 없습니다. 내부망에서만 띄우세요.

## IDE

Lombok을 사용합니다. IDE에서 컴파일 오류가 나면 아래를 확인하세요.

- **IntelliJ IDEA**: Lombok 플러그인 설치, `Settings > Build > Compiler > Annotation Processors`에서 `Enable annotation processing` 체크, Gradle JVM을 JDK 25로 지정
- **VS Code**: Extension Pack for Java 설치 (Lombok 지원 포함), JDK 설정에서 JDK 25 지정
