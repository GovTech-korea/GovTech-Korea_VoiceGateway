package com.govtech.voicegateway.domain.call.message;

/**
 * 규격 v2 오류 코드. {@code closesConnection}이 true면 오류를 보낸 뒤 연결을 닫는다.
 */
public enum ErrorCode {
	INVALID_MESSAGE(true),
	UNSUPPORTED_AUDIO(true),
	DUPLICATE_AGENT(true),
	DUPLICATE_CALL(true),
	INVALID_STATE(false),
	INVALID_FRAME(false);

	private final boolean closesConnection;

	ErrorCode(boolean closesConnection) {
		this.closesConnection = closesConnection;
	}

	public boolean closesConnection() {
		return closesConnection;
	}
}
