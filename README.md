# GovTech-Korea VoiceGateway

GovTech Korea 공모전 출품작 **모민(MOMIN, 모두의 민원)**의 음성 연계 게이트웨이입니다.
전화 서버(Asterisk)에서 통화 음성을 실시간으로 받아 STT·위험 탐지로 넘깁니다.

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

`http://localhost:8080`에서 실행됩니다. 아직 API가 없어 루트 경로는 404를 반환합니다.

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

## IDE

Lombok을 사용합니다. IDE에서 컴파일 오류가 나면 아래를 확인하세요.

- **IntelliJ IDEA**: Lombok 플러그인 설치, `Settings > Build > Compiler > Annotation Processors`에서 `Enable annotation processing` 체크, Gradle JVM을 JDK 25로 지정
- **VS Code**: Extension Pack for Java 설치 (Lombok 지원 포함), JDK 설정에서 JDK 25 지정
