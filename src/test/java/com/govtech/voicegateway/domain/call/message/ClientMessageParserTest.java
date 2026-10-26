package com.govtech.voicegateway.domain.call.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.govtech.voicegateway.domain.call.AgentState;
import com.govtech.voicegateway.domain.call.Role;

import tools.jackson.databind.json.JsonMapper;

class ClientMessageParserTest {

	private static final String AUDIO =
			"\"audio\":{\"encoding\":\"PCM16LE\",\"sampleRate\":16000,\"channels\":1,\"frameMs\":20}";

	private final JsonMapper jsonMapper = JsonMapper.builder().build();
	private final ClientMessageParser parser = new ClientMessageParser(jsonMapper);

	@Test
	@DisplayName("상담원 register를 해석한다")
	void register() {
		ClientMessage message = parser.parse("{\"type\":\"register\",\"role\":\"AGENT\",\"agentId\":\"agent-01\"," + AUDIO + "}");

		assertThat(message).isEqualTo(new ClientMessage.Register("agent-01"));
	}

	@Test
	@DisplayName("민원인 join을 해석한다")
	void join() {
		ClientMessage message = parser.parse("{\"type\":\"join\",\"role\":\"CITIZEN\",\"callId\":\"call_0001\"," + AUDIO + "}");

		assertThat(message).isEqualTo(new ClientMessage.Join("call_0001"));
	}

	@Test
	@DisplayName("status, accept, reject, hangup을 해석한다")
	void others() {
		assertThat(parser.parse("{\"type\":\"status\",\"state\":\"AWAY\"}")).isEqualTo(new ClientMessage.Status(AgentState.AWAY));
		assertThat(parser.parse("{\"type\":\"accept\",\"callId\":\"c1\"}")).isEqualTo(new ClientMessage.Accept("c1"));
		assertThat(parser.parse("{\"type\":\"reject\",\"callId\":\"c1\"}")).isEqualTo(new ClientMessage.Reject("c1"));
		assertThat(parser.parse("{\"type\":\"hangup\",\"callId\":\"c1\"}")).isEqualTo(new ClientMessage.Hangup("c1"));
	}

	@Test
	@DisplayName("audio가 규격과 다르면 UNSUPPORTED_AUDIO")
	void unsupportedAudio() {
		String text = "{\"type\":\"join\",\"role\":\"CITIZEN\",\"callId\":\"c1\","
				+ "\"audio\":{\"encoding\":\"PCM16LE\",\"sampleRate\":48000,\"channels\":1,\"frameMs\":20}}";

		assertThatThrownBy(() -> parser.parse(text))
				.isInstanceOfSatisfying(ProtocolException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED_AUDIO));
	}

	@ParameterizedTest
	@DisplayName("형식이 틀리면 INVALID_MESSAGE")
	@ValueSource(strings = {
			"not json",
			"[1,2]",
			"{\"type\":\"unknown\"}",
			"{\"type\":\"accept\"}",
			"{\"type\":\"accept\",\"callId\":\"has space\"}",
			"{\"type\":\"status\",\"state\":\"IN_CALL\"}",
			"{\"type\":\"join\",\"role\":\"AGENT\",\"callId\":\"c1\"," + AUDIO + "}",
			"{\"type\":\"register\",\"role\":\"AGENT\",\"agentId\":\"a1\"}"
	})
	void invalid(String text) {
		assertThatThrownBy(() -> parser.parse(text))
				.isInstanceOfSatisfying(ProtocolException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_MESSAGE));
	}

	@Test
	@DisplayName("게이트웨이 메시지는 JSON에 type 필드가 들어간다")
	void serverMessageHasType() {
		String json = jsonMapper.writeValueAsString(new ServerMessage.Connected("c1", Role.CITIZEN, 800));

		assertThat(json).contains("\"type\":\"connected\"", "\"callId\":\"c1\"", "\"peerRole\":\"CITIZEN\"", "\"delayMs\":800");
	}
}
