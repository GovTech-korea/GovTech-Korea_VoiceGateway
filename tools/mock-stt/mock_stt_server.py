"""가짜 STT 서버: STT 담당 서버(streaming-asr1)의 /ws/transcribe와 같은 형식으로, 정해진 대사를 자막처럼 돌려준다.

모델 없이 게이트웨이 ↔ STT 연결, 상담원 화면 자막, 위험 발언 자동 차단을 확인할 때 쓴다.
실제 음성 내용은 보지 않고, 받은 음성 길이(시간)에 맞춰 대사를 한 글자씩 흘려보낸다.

    pip install websockets
    python tools/mock-stt/mock_stt_server.py            # ws://localhost:8100/ws/transcribe
    python tools/mock-stt/mock_stt_server.py --port 9000

게이트웨이 STT 주소: 통합 콘솔(/test/console.html)의 "STT 서버"에 http://localhost:8100 을 넣는다.
"""

import argparse
import asyncio
import json

import websockets

SAMPLE_RATE = 16000
BYTES_PER_SECOND = SAMPLE_RATE * 2  # int16 모노

# (대사, 말하는 시간 초). 두 번째 대사에 욕설이 있어 게이트웨이의 위험 발언 차단이 동작한다
SCRIPT = [
    ("안녕하세요 집 앞 가로등이 며칠째 꺼져 있어서 전화드렸어요", 3.0),
    ("아니 왜 이렇게 처리가 늦어요 당신 개새끼야", 2.5),
    ("세종시 어진동 횡단보도 근처입니다", 2.0),
    ("내일 사무실로 찾아가서 가만두지 않을 거야", 2.5),
]
GAP_SECONDS = 0.8  # 대사 사이 침묵
PARTIAL_EVERY = 0.3  # 중간 결과 간격 (초)


async def handle(ws):
    await ws.send(json.dumps({"type": "ready", "sample_rate": SAMPLE_RATE, "language": "ko-KR"}))
    total = 0  # 통화 처음부터 받은 음성 (바이트)
    line = 0
    started_at = None  # 지금 대사의 시작 (통화 처음부터 초)
    last_end = 0.0  # 앞 대사가 끝난 때 (초)
    shown = 0  # 지금까지 보여 준 글자 수
    next_partial = 0.0
    async for message in ws:
        if isinstance(message, str):
            if json.loads(message).get("type") == "stop":
                break
            continue
        total += len(message)
        now = total / BYTES_PER_SECOND
        text, duration = SCRIPT[line % len(SCRIPT)]
        if started_at is None:
            if now - last_end < GAP_SECONDS:
                continue
            started_at, shown, next_partial = now, 0, now
            await ws.send(json.dumps({"type": "speech_start", "id": line, "audio_start": round(started_at, 2)}))
            continue
        elapsed = now - started_at
        if elapsed >= duration:
            await ws.send(json.dumps({"type": "final", "id": line, "text": text, "language": "ko-KR",
                                      "audio_end": round(now, 2), "stt_ms": 5.0}))
            line, started_at, last_end = line + 1, None, now
            continue
        if now >= next_partial:
            shown = max(shown + 1, int(len(text) * elapsed / duration))
            await ws.send(json.dumps({"type": "partial", "id": line, "text": text[:shown], "stt_ms": 6.0}))
            next_partial = now + PARTIAL_EVERY


async def main(port):
    async with websockets.serve(handle, "0.0.0.0", port):
        print(f"가짜 STT 서버: ws://localhost:{port}/ws/transcribe", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=8100)
    asyncio.run(main(parser.parse_args().port))
