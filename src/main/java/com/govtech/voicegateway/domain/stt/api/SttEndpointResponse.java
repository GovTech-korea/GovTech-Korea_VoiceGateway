package com.govtech.voicegateway.domain.stt.api;

import java.net.URI;
import java.util.Optional;

/**
 * @param url 실제로 연결할 WebSocket 주소 (정리된 형태). 정하지 않았으면 null
 */
public record SttEndpointResponse(String url) {

	static SttEndpointResponse of(Optional<URI> uri) {
		return new SttEndpointResponse(uri.map(URI::toString).orElse(null));
	}
}
