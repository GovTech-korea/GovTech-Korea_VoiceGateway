package com.govtech.voicegateway.domain.stt;

/**
 * STT 엔진 연결부. 엔진(AI 담당)이 정해지면 이 인터페이스의 구현만 바꾼다.
 */
public interface SttGateway {

	/**
	 * @param listener STT 결과를 받을 곳. 결과를 돌려주지 않는 연결부는 무시해도 된다
	 */
	SttStream open(String callId, SttAudioFormat format, SttListener listener);
}
