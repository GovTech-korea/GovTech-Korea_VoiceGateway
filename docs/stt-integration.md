# STT 연동 규격: 게이트웨이 ↔ STT·위험 탐지

- 대상: STT·위험 탐지(AI) 담당
- 버전: v0.1 초안 (2026-10-28)
- 상태: 1~5장은 **지금 게이트웨이에 구현된 내용**이고, 6장 전송 방식은 **제안안(미구현)** 입니다. 8장 체크리스트를 같이 정한 뒤 확정합니다.

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

## 6. 전송 방식: 게이트웨이 → STT 서버 (제안안, 미구현)

지금 게이트웨이는 STT로 보낼 음성을 **파일로만 저장**합니다(7장). 실제 STT로 실시간 전송하는 방식으로 아래를 제안합니다. 의견을 주시면 맞춰서 구현합니다.

### 6.1 연결

- **STT 쪽이 WebSocket 서버**를 열고, **게이트웨이가 접속**합니다. 나중에 클라우드 STT를 써도 같은 방향(게이트웨이가 보내는 쪽)입니다.
- 게이트웨이 설정에 STT 주소를 넣습니다: `voicegateway.stt.url=wss://<STT 서버>/stt`
- **통화 하나에 연결 하나.** 통화가 연결될 때 접속하고, 통화가 끝나면 닫습니다.
- Colab에서 띄울 때는 cloudflared 임시 터널로 `wss://xxxx.trycloudflare.com` 주소를 만들면 됩니다 (부록 A).

### 6.2 게이트웨이 → STT 메시지

**① 시작 (텍스트, 접속 직후 한 번)**
```json
{
  "type": "start",
  "callId": "call-0001",
  "speaker": "CITIZEN",
  "format": "PCM16_16K",
  "sampleRate": 16000,
  "frameMs": 20,
  "delayMs": 800
}
```
`delayMs`는 상담원 지연 시간입니다. 이 안에 판단해야 차단이 됩니다.

**② 음성 조각 (바이너리, 20ms마다)**

| 바이트 | 내용 |
|---|---|
| 0~3 | `sequence` (uint32, little-endian) |
| 4~11 | `receivedAtEpochMs` (int64, little-endian) |
| 12~ | PCM (2장 형식, 16kHz면 640바이트) |

```python
import struct
seq, received_at = struct.unpack_from("<Iq", message, 0)
pcm = message[12:]
```

**③ 끝 (텍스트, 통화 종료 시)**
```json
{"type": "end", "callId": "call-0001", "reason": "HANGUP_CITIZEN", "chunks": 1520}
```
`reason`: `HANGUP_CITIZEN`, `HANGUP_AGENT`, `DISCONNECTED`. 이 메시지 뒤 게이트웨이가 연결을 닫습니다.

### 6.3 STT → 게이트웨이 메시지 (같은 연결, 텍스트 JSON)

**위험 구간 (한 번 차단, 5.1과 같은 동작)**
```json
{"type": "risk", "fromEpochMs": 1793025159393, "toEpochMs": 1793025159993, "label": "ABUSE", "score": 0.93}
```
- `fromEpochMs`/`toEpochMs`: 위험 구간에 해당하는 조각들의 `receivedAtEpochMs` 중 처음과 끝
- `label`, `score`: 선택. 게이트웨이는 기록만 하고, 나중에 상담원 화면에 보여 줄 수 있음

**차단 유지 (5.2와 같은 동작)**
```json
{"type": "hold", "on": true}
```

**인식 결과 (선택)**
```json
{"type": "transcript", "fromSequence": 100, "toSequence": 152, "text": "...", "final": true}
```
지금은 기록만 합니다. 상담 기록·상담원 화면 자막 등에 쓸지는 협의합니다.

### 6.4 오류와 끊김

- STT 서버에 접속하지 못하거나 연결이 끊겨도 **통화는 계속됩니다.** 그 통화는 STT 없이 진행합니다 (MVP는 통화 중 재접속 없음).
- STT 쪽이 처리하지 못하는 메시지는 무시해도 됩니다. 게이트웨이도 모르는 `type`은 무시합니다.

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
- [ ] **전송 방식**: 6장 제안대로 갈지, 다른 방식(gRPC, 클라우드 STT API 등)인지
- [ ] **결과 형식**: 시각 구간(`fromEpochMs`/`toEpochMs`)으로 줄지, 순번(`sequence`)으로 줄지
- [ ] **판단 시간**: 조각을 받고 위험 판단까지 얼마나 걸리는지. 0.8초를 넘으면 지연을 늘릴지 (늘리면 통화가 더 늦게 들림)
- [ ] **부분 결과**: 말이 끝나기 전에 판단이 필요한지 (스트리밍 STT 여부)
- [ ] **상담원 음성**: 상담원 목소리도 STT가 필요한지 (지금은 민원인만)
- [ ] **차단 방식**: 위험하면 그 구간만 지울지(`risk`), 일정 시간 계속 막을지(`hold`)

## 부록 A. Colab에서 받는 쪽 예시 (6장 제안안 기준)

모델 자리는 비워 두고, 처음에는 "소리가 크면 위험"이라는 가짜 판단으로 흐름만 확인하는 예시입니다.

```python
# 셀 1: 준비
!pip -q install websockets numpy
!wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O cloudflared && chmod +x cloudflared
```

```python
# 셀 2: STT 서버 (모델은 detect 함수에)
import asyncio, json, struct
import numpy as np
import websockets

def detect(audio: np.ndarray) -> bool:
    """여기에 STT + 위험 판단 모델. 지금은 소리 크기로 흉내만 낸다."""
    return float(np.sqrt(np.mean(audio ** 2))) > 0.3

async def handle(ws):
    start = json.loads(await ws.recv())
    print("start", start)
    async for message in ws:
        if isinstance(message, bytes):
            seq, received_at = struct.unpack_from("<Iq", message, 0)
            audio = np.frombuffer(message[12:], dtype="<i2").astype(np.float32) / 32768.0
            if detect(audio):
                await ws.send(json.dumps({"type": "risk", "fromEpochMs": received_at, "toEpochMs": received_at}))
        else:
            event = json.loads(message)
            if event["type"] == "end":
                print("end", event)

server = await websockets.serve(handle, "0.0.0.0", 8765)
```

```python
# 셀 3: 공개 주소 만들기 → 출력된 https://xxxx.trycloudflare.com 을 wss://xxxx.trycloudflare.com/stt 로 게이트웨이에 설정
!nohup ./cloudflared tunnel --url http://localhost:8765 > tunnel.log 2>&1 &
!sleep 8 && grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' tunnel.log
```

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.1 | 2026-10-28 | 초안. 1~5장은 구현된 내용, 6장 전송 방식은 제안안 |
