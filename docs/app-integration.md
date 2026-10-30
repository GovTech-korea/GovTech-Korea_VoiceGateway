# 앱 연동 규격: WebSocket 음성

민원인 앱·상담원 앱(Android)과 VOICE_GATEWAY 사이의 연동 규격입니다.

- 버전: v2.3 (2026-10-30)
- 상태: 규격 확정, 게이트웨이 구현 진행 중

## 1. 전체 구조

```
민원인 앱 ──음성──▶ VOICE_GATEWAY ──(0.5~1초 지연)──▶ 상담원 앱
                        │
                        └─ 즉시 STT ─▶ 위험 감지 시 아직 안 나간 구간을 무음 처리

상담원 앱 ──음성──▶ VOICE_GATEWAY ──(지연 없음)──▶ 민원인 앱
```

- 두 앱은 서로 직접 연결하지 않습니다. **모든 음성은 게이트웨이를 거칩니다.**
- 민원인은 상담원을 고르지 않습니다. 전화를 걸면 **큐에 들어가고**, 게이트웨이가 **대기 중인 상담원에게 자동 배정**합니다.
- 배정되면 **상담원 앱에 벨이 울리고**, 상담원이 받아야 연결됩니다.

| 앱 | WebSocket 연결 |
|---|---|
| 상담원 앱 | 앱을 켜고 상담 준비를 시작할 때 연결해 **계속 유지**합니다. 여러 통화를 이 연결 하나로 받습니다 |
| 민원인 앱 | 전화를 걸 때 연결하고, **통화가 끝나면 닫습니다.** 통화 하나에 연결 하나 |

## 2. 연결 주소

| 환경 | 주소 |
|---|---|
| 개발 (같은 와이파이의 개발 PC) | `ws://<개발 PC IP>:8080/ws/v1/call` |
| 배포 | `wss://<서버 도메인>/ws/v1/call` (주소는 배포 시 안내) |

배포 환경에서는 마이크 음성이 오가므로 반드시 `wss://`(암호화)를 씁니다. 상담원 앱과 민원인 앱은 같은 주소를 씁니다.

## 3. 통화 흐름

### 상담원 앱

```
상담원 앱                                 게이트웨이
 │── 연결 ─────────────────────────────▶│
 │── register (상담원 ID) ──────────────▶│
 │◀───────────── registered (READY) ────│   대기 상태가 됨
 │                ...                    │
 │◀──────── ringing (통화 ID, 20초) ────│   민원인이 배정됨 → 벨 울림
 │── accept ───────────────────────────▶│   (거절하면 reject)
 │◀──────────────────── connected ──────│   음성 시작
 │◀═══════ 민원인 음성 (약 0.8초 지연) ══│
 │════════ 상담원 음성 ═════════════════▶│
 │── hangup ───────────────────────────▶│   (민원인이 끊어도 됨)
 │◀──────────────────── call-ended ─────│
 │◀──── status-changed (READY) ─────────│   다시 대기 상태
```

- **20초 안에 받지 않으면** 벨이 멈추고(`ring-cancelled`), 그 상담원은 **AWAY(자리 비움)**가 됩니다. 민원인은 다음 대기 상담원에게 다시 배정됩니다.
- **거절(`reject`)**하면 바로 다음 대기 상담원에게 넘어갑니다. 거절한 상담원은 READY 상태를 유지합니다.
  - 게이트웨이는 그 통화를 거절한 상담원 ID를 기억하고, **같은 통화로는 그 상담원에게 다시 벨을 울리지 않습니다** (같은 ID로 다시 접속해도 마찬가지).
  - **대기 중인 상담원이 모두 거절하면** 민원인은 대기열 맨 앞으로 돌아가 `queued`(순번 1)를 다시 받고, 거절하지 않은 상담원이 READY가 될 때까지 기다립니다. 게이트웨이가 통화를 끊지는 않습니다. 오래 기다리게 할지는 민원인 앱이 정합니다(예: 안내 후 `hangup`).
  - 이 통화를 거절한 상담원도 **뒤 순번의 다른 통화**는 받을 수 있습니다. 거절된 통화가 맨 앞에 있어도 뒤 통화의 배정을 막지 않습니다.
- 상담원은 통화 중이 아닐 때 `status`로 READY(대기)·AWAY(자리 비움)를 바꿀 수 있습니다. AWAY인 상담원에게는 배정하지 않습니다.

### 민원인 앱

```
민원인 앱                                 게이트웨이
 │── 연결 ─────────────────────────────▶│
 │── join (통화 ID) ───────────────────▶│   앱이 만든 통화 ID
 │◀──────────────── queued (순번 1) ────│   대기 상담원이 없으면 대기 (순번이 바뀔 때마다 다시 옴)
 │◀────────────────── agent-ringing ────│   상담원에게 벨이 울리는 중
 │◀──────────────────── connected ──────│   음성 시작
 │════════ 민원인 음성 ═════════════════▶│
 │◀═══════ 상담원 음성 ═════════════════│
 │── hangup ───────────────────────────▶│   (대기 중에도 가능)
 │◀──────────────────── call-ended ─────│   연결 닫기
```

- 대기 상담원이 있으면 `queued` 없이 바로 `agent-ringing`이 옵니다.
- 배정 순서: 민원인은 **먼저 온 순서**, 상담원은 **가장 오래 대기한 사람부터**입니다.

## 4. 음성 형식 (바이너리 메시지)

| 항목 | 값 |
|---|---|
| 인코딩 | PCM 16비트 signed, **little-endian** |
| 샘플링 | **16,000Hz** |
| 채널 | **1 (mono)** |
| 프레임 길이 | **20ms** = 320샘플 = **640바이트** |

- **`connected`를 받은 뒤부터** 바이너리 메시지 하나에 프레임 하나(640바이트)를 담아 20ms마다 보냅니다. 그 전에 보낸 음성은 버립니다.
- 헤더 없이 음성 바이트만 보냅니다 (WAV 헤더 넣지 않음).
- 게이트웨이가 보내는 상대 음성도 같은 형식입니다.
- 지연 구간에서 차단된 부분은 **무음(0으로 채운 바이트)**으로 옵니다. 앱은 그대로 재생하면 됩니다.

## 5. 텍스트 메시지 (JSON)

모든 텍스트 메시지에는 `type`이 있습니다. 공통으로 쓰는 `audio` 값은 v2에서 아래로 고정입니다.

```json
"audio": { "encoding": "PCM16LE", "sampleRate": 16000, "channels": 1, "frameMs": 20 }
```

### 상담원 앱 → 게이트웨이

| type | 예시 | 보낼 수 있을 때 |
|---|---|---|
| `register` | `{"type":"register","role":"AGENT","agentId":"agent-01","audio":{...}}` | 연결 직후 한 번 |
| `status` | `{"type":"status","state":"AWAY"}` (`READY` / `AWAY`) | 통화 중이 아닐 때 |
| `accept` | `{"type":"accept","callId":"call-0001"}` | `ringing`을 받은 뒤 |
| `reject` | `{"type":"reject","callId":"call-0001"}` | `ringing`을 받은 뒤 |
| `hangup` | `{"type":"hangup","callId":"call-0001"}` | 통화 중 |

- `agentId`: 1~64자, 영문·숫자·`-`·`_`. MVP는 로그인 없이 앱이 정한 값을 그대로 씁니다. 같은 `agentId`로 동시에 두 번 접속할 수 없습니다.

### 민원인 앱 → 게이트웨이

| type | 예시 | 보낼 수 있을 때 |
|---|---|---|
| `join` | `{"type":"join","role":"CITIZEN","callId":"call-0001","audio":{...}}` | 연결 직후 한 번 |
| `hangup` | `{"type":"hangup","callId":"call-0001"}` | 대기 중·통화 중 언제나 |

- `callId`: 민원인 앱이 만듭니다. 1~64자, 영문·숫자·`-`·`_`. 이미 사용 중인 `callId`로는 접속할 수 없습니다 (UUID 사용 권장).

### 게이트웨이 → 상담원 앱

| type | 예시 | 의미 |
|---|---|---|
| `registered` | `{"type":"registered","agentId":"agent-01","state":"READY"}` | 등록 완료, 대기 상태 |
| `status-changed` | `{"type":"status-changed","state":"AWAY","reason":"NO_ANSWER"}` | 상태 변경. reason: `REQUESTED`(내가 바꿈), `NO_ANSWER`(벨 미응답), `CALL_ENDED`(통화 종료 후 READY) |
| `ringing` | `{"type":"ringing","callId":"call-0001","timeoutMs":20000}` | 벨 울림. `timeoutMs` 안에 accept/reject |
| `ring-cancelled` | `{"type":"ring-cancelled","callId":"call-0001","reason":"TIMEOUT"}` | 벨 중지. reason: `TIMEOUT`, `CITIZEN_LEFT`(민원인이 끊음) |
| `connected` | `{"type":"connected","callId":"call-0001","peerRole":"CITIZEN","delayMs":800}` | 통화 연결, 음성 시작. `delayMs`는 민원인 음성 지연 시간 |
| `call-ended` | `{"type":"call-ended","callId":"call-0001","reason":"HANGUP_CITIZEN"}` | 통화 종료 |
| `transcript` | `{"type":"transcript","callId":"call-0001","utteranceId":"call-0001-3","text":"안녕하세","final":false}` | 민원인 말의 실시간 자막 (STT 서버가 연결돼 있을 때만). 같은 `utteranceId`로 중간 결과(`final:false`)가 여러 번 오고, 문장이 끝나면 `final:true`로 한 번 더 온다. 같은 줄을 덮어쓰면 된다 |
| `risk-detected` | `{"type":"risk-detected","callId":"call-0001","utteranceId":"call-0001-4","text":"…","riskType":"ABUSE","severity":"MEDIUM","target":null,"keyword":"개새끼","mutedFrames":40}` | 위험 발화를 찾아 그 발화에서 아직 들리지 않은 부분을 지우고 **차단 유지를 자동으로 켬** (발화마다 한 번). 상담원이 `PUT /api/v1/calls/{callId}/mute-hold` `{"hold":false}`로 풀 때까지 민원인 음성은 무음으로 간다. `riskType`: `ABUSE`, `THREAT`, `SEXUAL` / `severity`: `LOW`~`CRITICAL` / `mutedFrames`×20ms만큼 차단. 상담원 화면에 경고를 띄운다 |
| `mute-hold-changed` | `{"type":"mute-hold-changed","callId":"call-0001","hold":true}` | 위험 발화 감지(`risk-detected` 직후)나 STT가 차단 유지를 켜거나 끔. 화면의 차단 버튼을 "차단 중 · 눌러서 해제"로 맞춘다 |

### 게이트웨이 → 민원인 앱

| type | 예시 | 의미 |
|---|---|---|
| `queued` | `{"type":"queued","callId":"call-0001","position":2}` | 대기 중. 순번이 바뀔 때마다 다시 옴 |
| `agent-ringing` | `{"type":"agent-ringing","callId":"call-0001"}` | 상담원에게 연결 중 |
| `connected` | `{"type":"connected","callId":"call-0001","peerRole":"AGENT","delayMs":0}` | 통화 연결, 음성 시작 |
| `call-ended` | `{"type":"call-ended","callId":"call-0001","reason":"HANGUP_AGENT"}` | 통화 종료 |

`call-ended`의 reason: `HANGUP_CITIZEN`, `HANGUP_AGENT`, `DISCONNECTED`(상대 연결이 끊김).
MVP에서는 통화 중 상대 연결이 끊기면 통화를 끝냅니다 (민원인을 다시 큐에 넣지 않음).

### 오류 (양쪽 공통)

```json
{ "type": "error", "code": "DUPLICATE_AGENT", "message": "agent-01은 이미 접속해 있습니다" }
```

| code | 의미 | 연결 |
|---|---|---|
| `INVALID_MESSAGE` | JSON 형식·필드 규칙 위반 (agentId·callId 형식 등) | 종료 |
| `UNSUPPORTED_AUDIO` | `audio` 값이 규격과 다름 | 종료 |
| `DUPLICATE_AGENT` | 같은 `agentId`가 이미 접속 중 | 종료 |
| `DUPLICATE_CALL` | 같은 `callId`가 이미 사용 중 | 종료 |
| `INVALID_STATE` | 지금 보낼 수 없는 메시지 (예: 벨이 안 울렸는데 accept, 통화 중 status) | 유지 (메시지 무시) |
| `INVALID_FRAME` | 음성 바이트 수가 640이 아님 | 유지 (프레임 버림) |

"종료"인 오류는 error 메시지를 보낸 뒤 서버가 연결을 닫습니다 (close code 1008).

## 6. Android 구현 가이드

### 권한

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- 화면이 꺼지거나 앱이 백그라운드일 때도 통화·대기를 유지하려면 (Android 14+) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

`RECORD_AUDIO`는 실행 중에 사용자에게 요청해야 합니다. 상담원 앱은 대기 중에도 연결을 유지해야 하므로 포그라운드 서비스에서 WebSocket을 관리하는 것을 권장합니다.

### 녹음 (내 음성 → 게이트웨이), `connected` 이후

```kotlin
val sampleRate = 16_000
val frameBytes = 640 // 20ms

val minBuffer = AudioRecord.getMinBufferSize(
    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
)
val recorder = AudioRecord(
    MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 에코 제거·잡음 억제가 켜지는 통화용 입력
    sampleRate,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(minBuffer, frameBytes * 4)
)

recorder.startRecording()
val frame = ByteArray(frameBytes)
while (inCall) {
    var read = 0
    while (read < frameBytes) {
        val n = recorder.read(frame, read, frameBytes - read)
        if (n <= 0) break
        read += n
    }
    if (read == frameBytes) {
        webSocket.send(frame.toByteString()) // OkHttp: okio.ByteString
    }
}
recorder.stop()
recorder.release()
```

- Android 기기는 little-endian이라 `AudioRecord.read(ByteArray)`로 읽은 바이트를 그대로 보내면 됩니다.
- 입력 소스는 반드시 `VOICE_COMMUNICATION`을 씁니다. 상대 목소리가 내 스피커에서 나와 내 마이크로 다시 들어가면, 게이트웨이의 STT가 그 소리를 민원인 말로 잘못 인식합니다.

### 재생 (상대 음성 → 스피커)

```kotlin
val player = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setSampleRate(16_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
    )
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()

// WebSocketListener.onMessage(webSocket, bytes: ByteString)
override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
    jitterBuffer.offer(bytes.toByteArray()) // 재생 스레드에서 꺼내 player.write(...)
}
```

- 네트워크 사정으로 프레임이 불규칙하게 도착합니다. **3~5프레임(60~100ms)을 모은 뒤 재생을 시작**하면 끊김이 줄어듭니다.
- 통화 중에는 `AudioManager.mode = AudioManager.MODE_IN_COMMUNICATION`으로 설정합니다.

### 텍스트 메시지 처리 (상담원 앱 예시)

```kotlin
override fun onMessage(webSocket: WebSocket, text: String) {
    val msg = JSONObject(text)
    when (msg.getString("type")) {
        "registered" -> showState(msg.getString("state"))
        "status-changed" -> showState(msg.getString("state"))
        "ringing" -> showIncomingCall(msg.getString("callId"), msg.getLong("timeoutMs")) // 벨 + 받기/거절 버튼
        "ring-cancelled" -> dismissIncomingCall()
        "connected" -> startAudio()        // 녹음·재생 시작
        "call-ended" -> stopAudio()
        "error" -> handleError(msg.getString("code"))
    }
}
```

## 7. 확인 체크리스트

- [ ] 상담원 앱: register 후 `registered(READY)`를 받는다
- [ ] 민원인 앱: 대기 상담원이 없으면 `queued`, 있으면 `agent-ringing`을 받는다
- [ ] 상담원 앱에 벨이 울리고, accept하면 양쪽에 `connected`가 온다
- [ ] 20초 동안 받지 않으면 `ring-cancelled`와 `status-changed(AWAY, NO_ANSWER)`가 온다
- [ ] 바이너리 메시지가 정확히 640바이트다
- [ ] 상대 음성이 들린다 (민원인 → 상담원은 약 0.8초 늦게)
- [ ] 한쪽이 hangup하면 양쪽에 `call-ended`가 오고, 상담원은 다시 READY가 된다
- [ ] 이어폰 없이 스피커로 통화해도 내 목소리가 상대에게 되돌아가지 않는다 (에코)

## 8. 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v2.3 | 2026-10-30 | `risk-detected` 직후 차단 유지가 자동으로 켜지고 `mute-hold-changed`(hold=true)가 감. 상담원이 풀 때까지 유지 (메시지 형식 변경 없음) |
| v2.2 | 2026-10-29 | 상담원 앱에 `transcript`(실시간 자막), `risk-detected`(위험 발화 자동 차단 알림), `mute-hold-changed` 추가. 앱이 모르면 무시해도 통화는 그대로 동작 |
| v2.1 | 2026-10-26 | 3절에 상담원 전원 거절 시 동작 명시 (메시지 변경 없음) |
| v2 | 2026-10-26 | 큐·자동 배정·상담원 벨(ringing/accept/reject) 추가. 상담원 앱은 연결 유지(register), 민원인 앱은 통화마다 연결(join). `peer-joined`/`peer-left`/`joined`/`leave` 제거 → `connected`/`call-ended`/`hangup`으로 대체 |
| v1 | 2026-10-26 | 최초 작성 (같은 callId로 접속한 두 앱을 바로 연결) |
