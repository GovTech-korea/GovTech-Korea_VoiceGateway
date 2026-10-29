package com.govtech.voicegateway.domain.call.api;

import java.util.List;

import com.govtech.voicegateway.domain.call.CallCenterSnapshot;
import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttEndpoint;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.global.config.VoiceGatewayProperties;

/**
 * 게이트웨이 설정과 현재 상담 현황.
 *
 * @param sttRecording STT로 보낸 음성을 파일로 남기는지 (voicegateway.stt.dump-dir)
 * @param sttUrl       연결할 STT 서버 주소. 정하지 않았으면 null
 */
public record StatusResponse(
		long delayMs,
		long ringTimeoutMs,
		SttAudioFormat sttFormat,
		boolean sttRecording,
		String sttUrl,
		List<CallCenterSnapshot.AgentView> agents,
		List<String> queue,
		List<CallCenterSnapshot.CallView> calls) {

	public static StatusResponse from(CallCenterSnapshot snapshot, VoiceGatewayProperties properties,
			SttProperties sttProperties, SttEndpoint sttEndpoint) {
		return new StatusResponse(properties.delayMs(), properties.ringTimeoutMs(), sttProperties.format(),
				sttProperties.dumpDir() != null, sttEndpoint.current().map(Object::toString).orElse(null),
				snapshot.agents(), snapshot.queue(), snapshot.calls());
	}
}
