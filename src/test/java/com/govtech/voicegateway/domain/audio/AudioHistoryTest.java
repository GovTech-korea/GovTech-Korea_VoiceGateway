package com.govtech.voicegateway.domain.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AudioHistoryTest {

	private static byte[] frame(int value) {
		byte[] frame = new byte[4];
		Arrays.fill(frame, (byte) value);
		return frame;
	}

	@Test
	@DisplayName("받은 시각 구간의 프레임만 이어 붙이고, 오래된 것은 개수를 넘으면 버린다")
	void between() {
		AudioHistory history = new AudioHistory(3);
		history.add(frame(1), 100);
		history.add(frame(2), 120);
		history.add(frame(3), 140);
		history.add(frame(4), 160);

		assertThat(history.between(120, 140)).containsExactly(2, 2, 2, 2, 3, 3, 3, 3);
		assertThat(history.between(0, 110)).isEmpty(); // 100에 받은 것은 버려졌다
	}
}
