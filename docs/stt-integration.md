# STT 연동 규격: 게이트웨이 ↔ STT·위험 탐지

- 대상: STT·위험 탐지(AI) 담당
- 버전: v0.2 (2026-10-29)
- 상태: 1~7장 모두 **지금 게이트웨이에 구현된 내용**입니다. 6장 실시간 전송은 STT 담당 서버(streaming-asr1)의 `/ws/transcribe` 형식에 맞췄습니다. 8장은 같이 정할 것입니다.

## 1. 한눈에 보기

게이트웨이는 민원인 음성을 두 갈래로 보냅니다.

```
민원인 앱 ──20ms 조각──▶ 게이트웨이 ─┬─▶ STT·위험 탐지 (받는 즉시)
                                    │         │
                                    │         │ "이 구간 위험" (차단 요청)
                                    │         ▼
                                    └─▶ 지연 버퍼 ──0.8초 뒤──▶ 상담원 앱
                                          (차단된 구간은 무음으로 나감)
```

| 방향 | 내용 |
|---|---|
| 게이트웨이 → STT | 민원인 음성 조각(20ms)과 조각 정보(통화 ID, 순번, 받은 시각 등) |
| STT → 게이트웨이 | 위험 구간 차단 요청 (시각 구간, 또는 차단 유지 켜기/끄기) |

**시간 예산**: 상담원은 민원인 목소리를 **0.8초 늦게** 듣습니다 (설정 `voicegateway.delay-ms`, 기본 800). STT·위험 판단이 조각을 받은 뒤 이 시간 안에 차단 요청을 보내면, 상담원이 듣기 전에 지워집니다. 늦으면 이미 들린 뒤입니다.

## 2. 음성 데이터 형식

| 항목 | 값 |
|---|---|
| 인코딩 | PCM 16비트 부호 있는 정수, **little-endian**, 모노 |
| 헤더 | 없음 (WAV 헤더 없이 raw 바이트만) |
| 샘플링 | `16000` 또는 `8000` Hz. 게이트웨이 설정 `voicegateway.stt.format`으로 고름 |
| 조각 길이 | 20ms, 초당 50개 |
| 조각 크기 | 16kHz: **640바이트**(320샘플) / 8kHz: **320바이트**(160샘플) |

| `format` 값 | 내용 |
|---|---|
| `PCM16_16K` (기본) | 민원인 앱에서 받은 소리 그대로 |
| `PCM16_8K` | 16kHz를 8kHz로 변환. 3.6kHz 저역 통과 필터(31탭 FIR, 해밍 창)를 거친 뒤 두 샘플마다 하나를 남김. 전화망(8kHz) 음성으로 학습한 모델용 |

8kHz 변환 품질(테스트로 확인한 값): 1kHz 소리는 세기 95~105% 유지, 6kHz 소리(8kHz에서 표현할 수 없어 잡음으로 섞여 들어올 소리)는 1% 미만으로 줄어듦. 조각 경계에서 필터 상태를 이어 가서 끊김이 없음.

## 3. 조각마다 같이 가는 값

| 이름 | 타입 | 예시 | 설명 |
|---|---|---|---|
| `callId` | 문자열 | `call-0001` | 통화 ID. 영문·숫자·`-`·`_`, 1~64자. 민원인 앱이 정함 |
| `speaker` | 문자열 | `CITIZEN` | 말한 사람. 지금은 민원인만 보냄 |
| `sequence` | 정수 | `152` | 통화 안의 순번. 0부터 1씩 늘어남. 빠진 조각을 알 수 있음 |
| `receivedAtEpochMs` | 정수 (ms) | `1793025159393` | 게이트웨이가 이 조각을 받은 시각 (Unix epoch ms). **차단 요청은 이 값으로 구간을 지정** |
| `format` | 문자열 | `PCM16_16K` | `pcm`의 형식 (2장) |
| `pcm` | 바이트 | 640바이트 | 음성 데이터 |

- 시각은 **게이트웨이 시계** 기준입니다. STT 쪽 시계로 계산하지 말고, 받은 `receivedAtEpochMs`를 그대로 돌려주세요.
- 조각 하나 = 20ms이므로 `receivedAtEpochMs`가 같은 구간의 조각은 보통 20ms 간격입니다 (네트워크 때문에 약간 흔들릴 수 있음).

## 4. 스트림 수명

| 시점 | 일어나는 일 |
|---|---|
| 상담원이 전화를 받음 (`connected`) | 통화 하나에 STT 스트림 하나가 열림 |
| 통화 중 | 민원인 음성 조각을 받는 즉시 순서대로 보냄 |
| 통화 종료 (누가 끊든, 연결이 끊기든) | 스트림이 닫힘 |

- **대기열에서 기다리는 동안의 음성은 보내지 않습니다.** 통화가 연결된 뒤부터 보냅니다.
- **상담원 목소리는 보내지 않습니다** (8장에서 필요 여부 협의).
- STT 쪽이 느리거나 오류가 나도 **통화는 끊기지 않습니다.** 그 조각만 버리고 실패 개수를 셉니다.

## 5. 결과 돌려주기: 차단 API (구현됨)

STT·위험 탐지는 아래 HTTP API로 차단을 요청합니다. 6장의 WebSocket 방식이 정해지면 같은 동작을 그 연결로도 보낼 수 있습니다.

### 5.1 한 번 차단: 구간 지정

```
POST /api/v1/calls/{callId}/mute
Content-Type: application/json

{"fromEpochMs": 1793025159393, "toEpochMs": 1793025159993}
```

- 받은 시각이 `fromEpochMs` 이상 `toEpochMs` 이하인 조각 중, **아직 상담원에게 나가지 않은 것**을 무음으로 바꿉니다.
- 본문을 생략하면 아직 나가지 않은 구간 전체를 무음으로 바꿉니다.
- 한쪽만 줄 수도 있습니다 (`{"fromEpochMs": ...}`만 주면 그 시각 이후 전부).

응답 `200`:
```json
{"callId": "call-0001", "mutedFrames": 30}
```
`mutedFrames`는 이번에 무음 처리한 조각 수입니다 (×20ms). 0이면 이미 상담원에게 나간 뒤입니다(늦음).

### 5.2 차단 유지: 켜기 / 끄기

```
PUT /api/v1/calls/{callId}/mute-hold
Content-Type: application/json

{"hold": true}
```

- `true`: 아직 나가지 않은 음성을 무음으로 바꾸고, **켜 두는 동안 들어오는 음성도 계속 무음**으로 상담원에게 보냅니다.
- `false`: 해제. 그 뒤에 들어오는 음성부터 다시 들립니다.
- 같은 요청을 여러 번 보내도 결과가 같습니다. 통화가 끝나면 자동으로 해제됩니다.
- 차단 중에도 STT에는 원본이 계속 갑니다.

응답 `200`:
```json
{"callId": "call-0001", "hold": true, "mutedFrames": 40}
```

### 5.3 오류

| 상태 | 이유 |
|---|---|
| `404` | 통화 중이 아닌 `callId` (끝났거나 아직 연결 전) |
| `400` | 본문 형식 오류 (`fromEpochMs` > `toEpochMs`, `hold` 없음 등) |

### 5.4 예시

```bash
# 받은 시각 1793025159393 ~ 1793025159993 구간 차단
curl -X POST https://<게이트웨이 주소>/api/v1/calls/call-0001/mute \
  -H 'Content-Type: application/json' \
  -d '{"fromEpochMs":1793025159393,"toEpochMs":1793025159993}'
```

```python
import requests

GATEWAY = "https://<게이트웨이 주소>"

def mute_range(call_id: str, from_ms: int, to_ms: int) -> int:
    r = requests.post(f"{GATEWAY}/api/v1/calls/{call_id}/mute",
                      json={"fromEpochMs": from_ms, "toEpochMs": to_ms}, timeout=0.5)
    r.raise_for_status()
    return r.json()["mutedFrames"]  # 0이면 늦게 도착

def hold(call_id: str, on: bool) -> None:
    requests.put(f"{GATEWAY}/api/v1/calls/{call_id}/mute-hold", json={"hold": on}, timeout=0.5).raise_for_status()
```

> 지금은 인증이 없습니다. 시험용 주소는 팀 안에서만 공유합니다.

## 6. 실시간 전송: 게이트웨이 → STT 서버 (구현됨)

게이트웨이는 **STT 담당 서버(streaming-asr1)의 `/ws/transcribe`에 통화마다 WebSocket으로 접속**합니다. STT 서버 쪽은 고칠 것이 없습니다.

### 6.1 연결

- STT 서버 주소는 **실행 중에 바꿀 수 있습니다** (Colab·ngrok 주소가 바뀌어도 게이트웨이를 다시 켜지 않음). 다음에 연결되는 통화부터 적용됩니다.
  - 화면: 통합 콘솔(`/test/console.html`) → "STT 서버"에 Colab이 출력한 `https://…` 주소를 넣고 적용
  - API: `PUT /api/v1/stt/endpoint` `{"url": "https://xxxx.ngrok-free.app"}` (비우면 끔), 확인은 `GET /api/v1/stt/endpoint`, 서버가 응답하는지는 `GET /api/v1/stt/endpoint/health` (게이트웨이가 STT 서버의 `GET /health`를 부름. 200이면 응답함, 본문의 `model`·`device`를 콘솔에 표시)
  - 설정 파일: `voicegateway.stt.url` (시작할 때의 주소)
- 넣은 주소는 자동으로 맞춥니다: `https` → `wss`, `http` → `ws`, 경로가 없으면 `/ws/transcribe`, 쿼리가 없으면 `language=ko-KR`
- 상담원이 전화를 받을 때 접속하고, 통화가 끝나면 `{"type":"stop"}`을 보낸 뒤 닫습니다. ngrok 안내 페이지를 건너뛰도록 `ngrok-skip-browser-warning` 헤더를 붙입니다.

### 6.2 게이트웨이 → STT

| 메시지 | 내용 |
|---|---|
| 바이너리 | 16kHz 모노 int16 little-endian PCM, **100ms(3200바이트)씩** (20ms 조각 5개를 묶음, 헤더 없음) |
| 텍스트 `{"type":"stop"}` | 통화 종료 |

- 접속이 끝나기 전에 들어온 음성은 최대 5초까지 모아 두었다가 순서대로 보냅니다.
- `voicegateway.stt.format=PCM16_8K`이면 STT 서버로는 보내지 않습니다 (서버가 16kHz만 받음).

### 6.3 STT → 게이트웨이

| 메시지 | 게이트웨이가 하는 일 |
|---|---|
| `{"type":"ready",...}` | 없음 (16kHz가 아니면 경고 로그) |
| `{"type":"speech_start","id":0,"audio_start":2.4}` | 발화 시작 초를 조각 순번(÷20ms)으로 바꿔, **그 조각을 받은 시각**을 기억 |
| `{"type":"partial","id":0,"text":"안녕하세"}` | 상담원 앱에 자막(`transcript`, `final:false`) 전달 + 위험 단어 검사 |
| `{"type":"final","id":0,"text":"안녕하세요."}` | 상담원 앱에 자막(`final:true`) 전달 + 위험 단어 검사, 로그에 문장 기록 |
| `{"type":"discard","id":1}` | 그 발화를 잊음 |
| `{"type":"error","message":"..."}` | 경고 로그 |

**위험 판단 (지금은 게이트웨이의 1차 키워드 필터)**

- STT 서버는 아직 위험 판단을 하지 않으므로, 게이트웨이가 자막 글자에서 욕설(`ABUSE`/`MEDIUM`)·위협(`THREAT`/`HIGH`) 단어를 찾습니다. 위협 구절은 띄어쓰기를 지우고, 욕설 단어는 띄어쓰기를 그대로 두고 비교합니다 ("몇 시 발표"가 걸리지 않게).
- 찾으면 **그 발화의 시작 시각부터 아직 상담원에게 나가지 않은 부분을 차단**하고, **차단 유지를 켜서 상담원이 풀 때까지 민원인 음성을 계속 막습니다.** 상담원 앱에는 `risk-detected`와 `mute-hold-changed`(hold=true)를 한 번 보냅니다. 같은 발화의 중간 결과가 더 와도 차단 유지를 다시 켜지 않으므로, 상담원이 푼 뒤에는 새 위험 발화가 나올 때만 다시 막힙니다.
- 정상 민원에도 나오는 말("꺼져 있어요" 등)은 목록에 넣지 않았습니다 (오탐 방지).
- "새끼"는 동물·손가락에도 쓰므로 욕으로만 쓰이는 형태(`야 이 새끼`, `나쁜/미친 새끼`, `새끼야`·`새끼들아`, `새꺄`)일 때만 찾습니다. `이/그 새끼`만으로는 찾지 않습니다 ("이 새끼들 분양하려고요" 같은 동물 민원, 중간 결과가 "그 새끼"에서 잘리는 경우).
- 문맥 판단(누구를 향했는지 등)은 LLM의 몫입니다. 위험 판단을 STT 쪽에서 보내고 싶으면, 같은 연결로 아래 메시지를 보내면 게이트웨이가 그대로 처리합니다 (선택, 협의).

```json
{"type": "risk", "fromEpochMs": 1793025159393, "toEpochMs": 1793025159993, "target": "AGENT", "riskType": "ABUSE", "severity": "MEDIUM", "text": "..."}
{"type": "hold", "on": true}
```
`risk`는 그 구간을 지우고 차단 유지를 켜며(키워드 감지와 같음), `hold`는 5.2 차단 유지와 같고, 상담원 앱에 각각 `risk-detected`, `mute-hold-changed`가 갑니다.

### 6.4 오류와 끊김

- STT 서버에 접속하지 못하거나(10초 안에 응답 없음 포함) 연결이 끊겨도 **통화는 계속됩니다.** 그 통화는 자막 없이 진행합니다 (통화 중 재접속 없음). 로그에 `STT 서버 연결 실패`가 남습니다.

## 7. 지금 바로 시험하는 방법

실시간 연결이 없어도, STT가 받았을 음성을 그대로 받아 모델을 시험할 수 있습니다.

1. 게이트웨이를 기록 옵션으로 실행: `--voicegateway.stt.dump-dir=runtime/stt-dump`
2. 테스트 화면(`/test`)에서 민원인·상담원으로 통화
3. 기록 받기
   - 통합 콘솔(`/test/console.html`)의 "STT로 보낸 음성 듣기"에서 WAV로 듣거나 받기
   - 또는 `GET /api/v1/stt/recordings`(목록), `GET /api/v1/stt/recordings/{name}`(WAV)
   - 또는 서버의 `runtime/stt-dump/<callId>-<시작 ms>.pcm` (raw PCM, 2장 형식)

```python
import numpy as np
samples = np.frombuffer(open("call-0001-1793025159391.pcm", "rb").read(), dtype="<i2")  # int16 little-endian
audio = samples.astype(np.float32) / 32768.0   # 대부분의 STT 모델 입력 (-1.0 ~ 1.0)
frames = audio.reshape(-1, 320)                 # 16kHz 기준 20ms 조각
```

```bash
ffplay -f s16le -ar 16000 -ac 1 call-0001-1793025159391.pcm          # 바로 듣기
ffmpeg -f s16le -ar 16000 -ac 1 -i call-0001-1793025159391.pcm out.wav  # WAV로 변환
```

## 8. 같이 정할 것 (체크리스트)

- [ ] **샘플링**: 모델이 16kHz를 받는지, 8kHz를 받는지 → 게이트웨이 설정 하나로 바꿈
- [x] **전송 방식**: streaming-asr1의 `/ws/transcribe` 형식으로 연결 (6장, 2026-10-29)
- [ ] **결과 형식**: 시각 구간(`fromEpochMs`/`toEpochMs`)으로 줄지, 순번(`sequence`)으로 줄지
- [ ] **판단 시간**: 조각을 받고 위험 판단까지 얼마나 걸리는지. 0.8초를 넘으면 지연을 늘릴지 (늘리면 통화가 더 늦게 들림)
- [ ] **부분 결과**: 말이 끝나기 전에 판단이 필요한지 (스트리밍 STT 여부)
- [ ] **상담원 음성**: 상담원 목소리도 STT가 필요한지 (지금은 민원인만)
- [ ] **차단 방식**: 위험하면 그 구간만 지울지(`risk`), 일정 시간 계속 막을지(`hold`)

## 부록 A. Colab STT 서버와 연결하기

1. STT 담당 노트북 `streaming_asr1_colab.ipynb`를 Colab에서 열고 위에서부터 실행 (T4 GPU, 보안 비밀 `NGROK_AUTHTOKEN`)
2. 셀 3이 출력한 `웹 클라이언트 : https://xxxx.ngrok-free.app` 주소를 복사
3. 게이트웨이 통합 콘솔(`/test/console.html`) → "STT 서버"에 붙여 넣고 **적용**
4. 상담원·민원인 화면에서 통화 → 상담원 화면 "실시간 자막"에 글자가 뜨는지 확인

ngrok 무료 주소는 노트북을 다시 실행할 때마다 바뀔 수 있습니다 (`NGROK_DOMAIN`을 쓰면 고정). 바뀌면 3번만 다시 하면 됩니다.

## 부록 B. 모델 없이 시험하기: 가짜 STT 서버

`tools/mock-stt/mock_stt_server.py`는 위 서버와 같은 형식으로 **정해진 대사**(정상 민원, 욕설, 위협)를 자막처럼 돌려줍니다. GPU·모델 없이 연결, 자막, 자동 차단을 확인할 때 씁니다.

```bash
pip install websockets
python tools/mock-stt/mock_stt_server.py           # ws://localhost:8100/ws/transcribe
```
통합 콘솔의 "STT 서버"에 `http://localhost:8100`을 넣고 통화하면 됩니다.

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.2 | 2026-10-29 | 6장을 STT 담당 서버(streaming-asr1) `/ws/transcribe` 형식으로 구현, STT 주소 실행 중 변경, 키워드 1차 필터·자동 차단, 부록을 Colab 연결·가짜 STT 서버로 교체 |
| v0.1 | 2026-10-28 | 초안. 1~5장은 구현된 내용, 6장 전송 방식은 제안안 |
