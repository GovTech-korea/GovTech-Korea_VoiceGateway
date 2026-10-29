package com.govtech.voicegateway.domain.stt;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 지금 연결할 STT 서버 주소. Colab·ngrok 주소는 다시 켤 때마다 바뀌므로, 실행 중에 API로 바꿀 수 있게 따로 둔다.
 * 바꾼 주소는 다음에 연결되는 통화부터 쓴다.
 * <p>
 * 민원인 음성이 그 주소로 가므로, 실행 중 변경은 {@link #ALLOWED_HOST_SUFFIXES}(Colab 터널·로컬 시험)로만 허용하고 모두 로그에 남긴다.
 * 다른 주소는 설정 파일({@code voicegateway.stt.url})로만 정할 수 있다.
 */
@Slf4j
@Component
public class SttEndpoint {

	/** 실행 중에 바꿀 수 있는 STT 서버 호스트: ngrok·Cloudflare 임시 터널, 로컬 시험 */
	static final List<String> ALLOWED_HOST_SUFFIXES = List.of(".ngrok-free.app", ".ngrok-free.dev", ".ngrok.app",
			".ngrok.io", ".trycloudflare.com");
	static final List<String> ALLOWED_LOCAL_HOSTS = List.of("localhost", "127.0.0.1");

	static final String DEFAULT_PATH = "/ws/transcribe";
	static final String DEFAULT_QUERY = "language=ko-KR";

	private final AtomicReference<URI> current = new AtomicReference<>();

	public SttEndpoint(SttProperties properties) {
		if (properties.url() != null) {
			current.set(normalize(properties.url().toString()));
		}
	}

	public Optional<URI> current() {
		return Optional.ofNullable(current.get());
	}

	/**
	 * 실행 중 변경. 허용한 호스트만 받는다.
	 *
	 * @param url 비우면(null·빈 문자열) STT 서버로 보내지 않는다
	 * @throws IllegalArgumentException 주소 형식이 아니거나 허용하지 않는 호스트일 때
	 */
	public Optional<URI> set(String url) {
		URI uri = url == null || url.isBlank() ? null : normalize(url);
		if (uri != null && !isAllowed(uri.getHost())) {
			throw new IllegalArgumentException("실행 중에는 ngrok·trycloudflare 주소나 localhost만 쓸 수 있습니다: " + uri.getHost());
		}
		URI previous = current.getAndSet(uri);
		log.info("STT 서버 주소 변경: {} → {}", previous, uri);
		return Optional.ofNullable(uri);
	}

	static boolean isAllowed(String host) {
		String lower = host.toLowerCase(Locale.ROOT);
		return ALLOWED_LOCAL_HOSTS.contains(lower) || ALLOWED_HOST_SUFFIXES.stream().anyMatch(lower::endsWith);
	}

	/**
	 * Colab에서 출력되는 주소를 그대로 붙여 넣어도 되게 맞춘다.
	 * https → wss, http → ws, 경로가 없으면 /ws/transcribe, 쿼리가 없으면 language=ko-KR.
	 *
	 * @throws IllegalArgumentException 주소 형식이 아니거나 http(s)·ws(s)가 아닐 때
	 */
	static URI normalize(String url) {
		try {
			URI uri = new URI(url.trim());
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
			String wsScheme = switch (scheme) {
				case "https", "wss" -> "wss";
				case "http", "ws" -> "ws";
				default -> throw new IllegalArgumentException("ws://, wss://, http://, https:// 주소만 쓸 수 있습니다: " + url);
			};
			if (uri.getHost() == null) {
				throw new IllegalArgumentException("호스트가 없는 주소입니다: " + url);
			}
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")
					? DEFAULT_PATH : uri.getRawPath();
			String query = uri.getRawQuery() == null ? DEFAULT_QUERY : uri.getRawQuery();
			String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
			// 받은 그대로(인코딩된 채) 이어 붙인다. URI의 여러 인자 생성자는 %를 다시 인코딩한다
			return new URI(wsScheme + "://" + uri.getHost() + port + path + "?" + query);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("주소 형식이 아닙니다: " + url, e);
		}
	}
}
