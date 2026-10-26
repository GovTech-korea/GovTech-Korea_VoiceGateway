# 앱 연동 규격: WebSocket 음성

민원인 앱·상담원 앱(Android)과 VOICE_GATEWAY 사이의 음성 연동 규격입니다.

- 버전: v1 (2026-10-26)
- 상태: 규격 확정, 게이트웨이 구현 진행 중

## 1. 전체 구조

```
민원인 앱 ──음성──▶ VOICE_GATEWAY ──(0.5~1초 지연)──▶ 상담원 앱
                        │
                        └─ 즉시 STT ─▶ 위험 감지 시 아직 안 나간 구간을 무음 처리

상담원 앱 ──음성──▶ VOICE_GATEWAY ──(지연 없음)──▶ 민원인 앱
```

- 두 앱은 서로 직접 연결하지 않습니다. **모든 음성은 게이트웨이를 거칩니다.**
- 앱 하나가 통화 하나에 **WebSocket 연결 하나**를 씁니다. 그 연결로 내 음성을 보내고 상대 음성을 받습니다.
- 민원인 앱과 상담원 앱은 같은 방식으로 연결하고, 접속할 때 보내는 `role` 값만 다릅니다.

## 2. 연결 주소

| 환경 | 주소 |
|---|---|
| 개발 (같은 와이파이의 개발 PC) | `ws://<개발 PC IP>:8080/ws/v1/call` |
| 배포 | `wss://<서버 도메인>/ws/v1/call` (주소는 배포 시 안내) |

배포 환경에서는 마이크 음성이 오가므로 반드시 `wss://`(암호화)를 씁니다.

## 3. 통화 흐름

```
앱                                   게이트웨이
 │── WebSocket 연결 ──────────────────▶│
 │── join (텍스트) ───────────────────▶│  통화 ID·역할·음성 형식 확인
 │◀──────────────────── joined (텍스트) ─│
 │◀─────────────── peer-joined (텍스트) ─│  상대가 들어오면 (이미 있으면 joined에 표시)
 │── 음성 프레임 (바이너리, 20ms마다) ──▶│
 │◀──────── 상대 음성 프레임 (바이너리) ─│
 │◀──────────────── peer-left (텍스트) ─│  상대가 나가면
 │── leave (텍스트) 또는 연결 종료 ──────▶│
```

- **같은 `callId`로 접속한 민원인 1명과 상담원 1명**이 연결됩니다.
- 상대가 아직 없어도 음성을 보내도 됩니다. 상대가 없는 동안의 음성은 상대에게 전달되지 않습니다.
- `callId`를 어떻게 정해 두 앱에 알려줄지는 MVP에서는 앱 쪽에서 정합니다 (예: 상담원 앱이 만든 ID를 민원인 앱에 입력·QR).

## 4. 음성 형식 (바이너리 메시지)

| 항목 | 값 |
|---|---|
| 인코딩 | PCM 16비트 signed, **little-endian** |
| 샘플링 | **16,000Hz** |
| 채널 | **1 (mono)** |
| 프레임 길이 | **20ms** = 320샘플 = **640바이트** |

- 바이너리 메시지 하나에 **프레임 하나(640바이트)**를 담아 20ms마다 보냅니다.
- 헤더 없이 음성 바이트만 보냅니다 (WAV 헤더 넣지 않음).
- 게이트웨이가 보내는 상대 음성도 같은 형식입니다.
- 지연 구간에서 차단된 부분은 **무음(0으로 채운 바이트)**으로 옵니다. 앱은 따로 처리할 필요 없이 그대로 재생하면 됩니다.

## 5. 텍스트 메시지 (JSON)

모든 텍스트 메시지에는 `type`이 있습니다.

### 앱 → 게이트웨이

**join** — 연결 직후 한 번

```json
{
  "type": "join",
  "callId": "call-20261026-0001",
  "role": "CITIZEN",
  "audio": {
    "encoding": "PCM16LE",
    "sampleRate": 16000,
    "channels": 1,
    "frameMs": 20
  }
}
```

| 필드 | 규칙 |
|---|---|
| `callId` | 1~64자, 영문·숫자·`-`·`_` |
| `role` | `CITIZEN`(민원인) 또는 `AGENT`(상담원) |
| `audio` | v1은 위 값만 허용 |

**leave** — 통화를 끝낼 때 (보내지 않고 연결만 끊어도 됩니다)

```json
{ "type": "leave" }
```

### 게이트웨이 → 앱

**joined** — join 성공

```json
{ "type": "joined", "callId": "call-20261026-0001", "role": "CITIZEN", "peerConnected": false, "delayMs": 800 }
```

- `peerConnected`: 상대가 이미 접속해 있으면 `true`
- `delayMs`: 민원인 → 상담원 음성 지연 시간 (참고용)

**peer-joined / peer-left** — 상대가 들어오거나 나감

```json
{ "type": "peer-joined", "role": "AGENT" }
{ "type": "peer-left", "role": "AGENT" }
```

**error**

```json
{ "type": "error", "code": "ROLE_TAKEN", "message": "이 통화에 이미 AGENT가 접속해 있습니다" }
```

| code | 의미 | 연결 |
|---|---|---|
| `INVALID_JOIN` | join 형식 오류 (callId·role 규칙 위반, JSON 오류) | 종료 |
| `UNSUPPORTED_AUDIO` | `audio` 값이 규격과 다름 | 종료 |
| `ROLE_TAKEN` | 같은 통화에 같은 역할이 이미 접속 | 종료 |
| `NOT_JOINED` | join 전에 음성을 보냄 | 유지 (그 음성은 버림) |
| `INVALID_FRAME` | 음성 바이트 수가 640이 아님 | 유지 (그 프레임은 버림) |

"종료"인 오류는 error 메시지를 보낸 뒤 서버가 연결을 닫습니다 (close code 1008).

## 6. Android 구현 가이드

### 권한

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- 화면이 꺼지거나 앱이 백그라운드일 때도 통화를 유지하려면 (Android 14+) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

`RECORD_AUDIO`는 실행 중에 사용자에게 요청해야 합니다.

### 녹음 (내 음성 → 게이트웨이)

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

### 텍스트 메시지 받기

```kotlin
override fun onMessage(webSocket: WebSocket, text: String) {
    when (JSONObject(text).getString("type")) {
        "joined" -> { /* 녹음 시작 */ }
        "peer-joined" -> { /* 통화 연결됨 표시 */ }
        "peer-left" -> { /* 상대 종료 표시 */ }
        "error" -> { /* code에 따라 처리 */ }
    }
}
```

## 7. 확인 체크리스트

- [ ] join 후 `joined`를 받는다
- [ ] 같은 callId로 두 앱이 접속하면 양쪽에 상대 접속이 표시된다
- [ ] 바이너리 메시지가 정확히 640바이트다
- [ ] 상대 음성이 들린다 (민원인 → 상담원은 약 0.8초 늦게)
- [ ] 이어폰 없이 스피커로 통화해도 내 목소리가 상대에게 되돌아가지 않는다 (에코)

## 8. 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v1 | 2026-10-26 | 최초 작성 |
