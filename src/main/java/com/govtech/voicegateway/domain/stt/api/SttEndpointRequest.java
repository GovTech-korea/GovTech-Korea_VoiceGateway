package com.govtech.voicegateway.domain.stt.api;

import jakarta.validation.constraints.Size;

/**
 * @param url STT 서버 주소. Colab에서 출력된 https 주소를 그대로 넣어도 된다. 비우면 연결하지 않는다
 */
public record SttEndpointRequest(@Size(max = 2048) String url) {
}
