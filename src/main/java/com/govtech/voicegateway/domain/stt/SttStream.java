package com.govtech.voicegateway.domain.stt;

/**
 * 통화 하나의 STT 스트림. 구현은 막히지 않아야 한다: 음성 수신 스레드에서 20ms마다 불린다.
 * 네트워크 전송처럼 느린 일은 구현이 자기 스레드로 넘긴다.
 */
public interface SttStream {

	void send(SttChunk chunk);

	/** 통화 종료. 이후 send는 오지 않는다. 상담 상태 잠금 안에서 불리므로 이것도 막히지 않아야 한다 */
	void close();
}
