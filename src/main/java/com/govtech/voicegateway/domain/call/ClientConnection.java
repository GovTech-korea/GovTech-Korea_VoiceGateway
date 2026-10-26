package com.govtech.voicegateway.domain.call;

import com.govtech.voicegateway.domain.call.message.ServerMessage;

/**
 * 앱 연결 하나. 상담 로직이 WebSocket에 직접 의존하지 않도록 둔 경계다.
 * <p>
 * 구현체는 보내기·닫기에서 호출한 스레드를 막지 않아야 한다. {@link CallCoordinator}가 상태 잠금을
 * 쥔 채로 호출하기 때문이다. 보낸 순서는 지켜야 한다.
 */
public interface ClientConnection {

	String id();

	void send(ServerMessage message);

	/** 상대 음성 프레임 (PCM16 LE 16kHz mono 20ms) */
	void sendAudio(byte[] frame);

	/** 정상 종료 (통화 끝) */
	void close();

	/** 규격 위반으로 종료 (close code 1008) */
	void closeForPolicyViolation();
}
