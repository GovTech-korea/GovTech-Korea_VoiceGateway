package com.govtech.voicegateway.domain.call.message;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.govtech.voicegateway.domain.call.AgentState;
import com.govtech.voicegateway.domain.call.Role;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 앱이 보낸 텍스트 메시지를 규격 v2대로 검증해 {@link ClientMessage}로 바꾼다.
 * 규칙 위반은 {@link ProtocolException}으로 알린다.
 */
@Component
@RequiredArgsConstructor
public class ClientMessageParser {

	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private final JsonMapper jsonMapper;

	public ClientMessage parse(String text) {
		JsonNode node;
		try {
			node = jsonMapper.readTree(text);
		} catch (JacksonException e) {
			throw invalid("JSON 형식이 아닙니다");
		}
		if (node == null || !node.isObject()) {
			throw invalid("JSON 객체가 아닙니다");
		}
		String type = string(node, "type");
		return switch (type) {
			case "register" -> {
				requireRole(node, Role.AGENT);
				requireAudio(node);
				yield new ClientMessage.Register(id(node, "agentId"));
			}
			case "join" -> {
				requireRole(node, Role.CITIZEN);
				requireAudio(node);
				yield new ClientMessage.Join(id(node, "callId"));
			}
			case "status" -> new ClientMessage.Status(status(node));
			case "accept" -> new ClientMessage.Accept(id(node, "callId"));
			case "reject" -> new ClientMessage.Reject(id(node, "callId"));
			case "hangup" -> new ClientMessage.Hangup(id(node, "callId"));
			default -> throw invalid("알 수 없는 type: " + type);
		};
	}

	private static void requireRole(JsonNode node, Role expected) {
		String role = string(node, "role");
		if (!expected.name().equals(role)) {
			throw invalid("role은 " + expected + "여야 합니다");
		}
	}

	/** v2는 PCM16LE, 16kHz, mono, 20ms만 허용한다. */
	private static void requireAudio(JsonNode node) {
		JsonNode audio = node.get("audio");
		if (audio == null || !audio.isObject()) {
			throw invalid("audio가 없습니다");
		}
		boolean supported = "PCM16LE".equals(text(audio, "encoding"))
				&& number(audio, "sampleRate") == 16000
				&& number(audio, "channels") == 1
				&& number(audio, "frameMs") == 20;
		if (!supported) {
			throw new ProtocolException(ErrorCode.UNSUPPORTED_AUDIO,
					"audio는 PCM16LE, 16000Hz, 1채널, 20ms만 지원합니다");
		}
	}

	private static AgentState status(JsonNode node) {
		String state = string(node, "state");
		return switch (state) {
			case "READY" -> AgentState.READY;
			case "AWAY" -> AgentState.AWAY;
			default -> throw invalid("state는 READY 또는 AWAY여야 합니다");
		};
	}

	private static String id(JsonNode node, String field) {
		String value = string(node, field);
		if (!ID.matcher(value).matches()) {
			throw invalid(field + "는 1~64자의 영문·숫자·-·_여야 합니다");
		}
		return value;
	}

	private static String string(JsonNode node, String field) {
		String value = text(node, field);
		if (value == null) {
			throw invalid(field + "가 없습니다");
		}
		return value;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isString() ? value.asString() : null;
	}

	private static long number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isIntegralNumber() ? value.asLong() : -1;
	}

	private static ProtocolException invalid(String message) {
		return new ProtocolException(ErrorCode.INVALID_MESSAGE, message);
	}
}
