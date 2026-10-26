package com.govtech.voicegateway.domain.call.message;

/**
 * 앱이 규격에 맞지 않는 메시지를 보냈을 때 던진다. 처리부가 error 메시지로 바꿔 앱에 알린다.
 */
public class ProtocolException extends RuntimeException {

	private final ErrorCode code;

	public ProtocolException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}
}
