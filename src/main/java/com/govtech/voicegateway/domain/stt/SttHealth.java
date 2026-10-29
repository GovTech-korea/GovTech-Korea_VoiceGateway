package com.govtech.voicegateway.domain.stt;

/**
 * STT 서버 상태 확인 결과 ({@code GET <STT 서버>/health}).
 *
 * @param url        확인한 주소 (정하지 않았으면 null)
 * @param configured STT 주소가 정해져 있는지
 * @param reachable  2초 안에 200으로 응답했는지
 * @param latencyMs  응답까지 걸린 시간. 확인하지 않았으면 null
 * @param model      STT 서버가 알려 준 모델 이름 (없으면 null)
 * @param device     STT 서버가 알려 준 장치 (예: gpu:0, 없으면 null)
 * @param error      응답하지 않은 이유 (응답했으면 null)
 */
public record SttHealth(String url, boolean configured, boolean reachable, Long latencyMs, String model, String device,
		String error) {

	static SttHealth notConfigured() {
		return new SttHealth(null, false, false, null, null, null, null);
	}
}
