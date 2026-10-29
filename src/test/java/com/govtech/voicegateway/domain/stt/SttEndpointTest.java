package com.govtech.voicegateway.domain.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SttEndpointTest {

	private final SttEndpoint endpoint = new SttEndpoint(new SttProperties(SttAudioFormat.PCM16_16K, null, null));

	@Test
	@DisplayName("Colab이 출력한 https 주소를 넣으면 wss + /ws/transcribe + language=ko-KR로 맞춘다")
	void normalizesColabUrl() {
		assertThat(endpoint.set("https://abc.ngrok-free.app")).contains(URI.create("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR"));
		assertThat(endpoint.set(" https://abc.ngrok-free.app/ ")).contains(URI.create("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR"));
	}

	@Test
	@DisplayName("경로·쿼리·포트를 직접 주면 그대로 둔다")
	void keepsGivenPathAndQuery() {
		assertThat(endpoint.set("ws://localhost:8100/ws/transcribe?language=auto"))
				.contains(URI.create("ws://localhost:8100/ws/transcribe?language=auto"));
		assertThat(SttEndpoint.normalize("http://10.0.0.5:8100")).isEqualTo(URI.create("ws://10.0.0.5:8100/ws/transcribe?language=ko-KR"));
		assertThat(SttEndpoint.normalize("https://abc.ngrok-free.app/ws/transcribe?language=ko-KR&hint=a%20b"))
				.hasToString("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR&hint=a%20b");
	}

	@Test
	@DisplayName("실행 중에는 ngrok·trycloudflare·localhost만 받는다 (민원인 음성이 가는 곳)")
	void allowsOnlyTunnelsAndLocal() {
		assertThat(endpoint.set("https://abc.trycloudflare.com")).isPresent();
		assertThat(endpoint.set("http://127.0.0.1:8100")).isPresent();
		for (String bad : new String[] { "https://evil.example.com", "http://169.254.169.254", "http://10.0.0.5:8100",
				"https://ngrok-free.app.evil.com" }) {
			assertThatThrownBy(() -> endpoint.set(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(endpoint.current()).contains(URI.create("ws://127.0.0.1:8100/ws/transcribe?language=ko-KR"));
	}

	@Test
	@DisplayName("비우면 STT 서버로 보내지 않는다")
	void clear() {
		endpoint.set("https://abc.ngrok-free.app");

		assertThat(endpoint.set("")).isEmpty();
		assertThat(endpoint.current()).isEmpty();
	}

	@Test
	@DisplayName("http(s)·ws(s)가 아니거나 호스트가 없으면 거부하고, 기존 주소는 그대로 둔다")
	void rejectsBadUrl() {
		endpoint.set("https://abc.ngrok-free.app");

		assertThatThrownBy(() -> endpoint.set("ftp://abc")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> endpoint.set("abc.ngrok-free.app")).isInstanceOf(IllegalArgumentException.class);
		assertThat(endpoint.current()).contains(URI.create("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR"));
	}

	@Test
	@DisplayName("설정 파일의 voicegateway.stt.url로 시작 주소를 정한다")
	void initialFromProperties() {
		SttEndpoint configured = new SttEndpoint(
				new SttProperties(SttAudioFormat.PCM16_16K, null, URI.create("https://abc.ngrok-free.app")));

		assertThat(configured.current()).contains(URI.create("wss://abc.ngrok-free.app/ws/transcribe?language=ko-KR"));
	}
}
