package com.govtech.voicegateway.domain.stt;

/**
 * STT 엔진이 돌려주는 결과를 받는 쪽 (통화마다 하나). STT 연결 스레드에서 불리므로 막히지 않아야 한다.
 */
public interface SttListener {

	/** 아무것도 하지 않는 리스너 (결과를 돌려주지 않는 연결부용) */
	SttListener NONE = new SttListener() {
	};

	/** 인식 결과. 말하는 도중에는 isFinal=false로 여러 번, 문장이 끝나면 isFinal=true로 한 번 */
	default void onTranscript(SttTranscript transcript) {
	}

	/** 위험 구간. 게이트웨이는 그 구간을 상담원에게 무음으로 보낸다 */
	default void onRisk(SttRisk risk) {
	}

	/** 차단 유지 켜기/끄기 요청 */
	default void onHold(boolean on) {
	}
}
