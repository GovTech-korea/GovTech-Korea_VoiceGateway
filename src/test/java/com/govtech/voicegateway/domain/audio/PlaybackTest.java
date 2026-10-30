package com.govtech.voicegateway.domain.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlaybackTest {

	private static final long MS = 1_000_000L;

	private static byte[] frame(int value) {
		byte[] frame = new byte[640];
		Arrays.fill(frame, (byte) value);
		return frame;
	}

	@Test
	@DisplayName("재생은 20ms에 한 프레임씩 내보내고, 멈추면 남은 프레임을 버린다")
	void playsInRealTime() {
		long[] now = { 0 };
		DelayLines lines = new DelayLines(() -> now[0]);
		List<byte[]> received = new ArrayList<>();

		Playback playback = lines.play(List.of(frame(1), frame(2), frame(3)), received::add);
		lines.drainAll();
		assertThat(received).containsExactly(frame(1));
		now[0] = 25 * MS;
		lines.drainAll();
		assertThat(received).containsExactly(frame(1), frame(2));

		playback.stop();
		now[0] = 100 * MS;
		lines.drainAll();
		assertThat(received).hasSize(2);
		assertThat(playback.isPlaying()).isFalse();
	}
}
