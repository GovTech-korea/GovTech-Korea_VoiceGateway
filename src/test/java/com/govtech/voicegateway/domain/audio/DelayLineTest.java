package com.govtech.voicegateway.domain.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelayLineTest {

	private static final long MS = 1_000_000L;

	private final List<byte[]> received = new ArrayList<>();
	private final DelayLine line = new DelayLine(Duration.ofMillis(800), received::add);

	private static byte[] frame(int value) {
		byte[] frame = new byte[640];
		Arrays.fill(frame, (byte) value);
		return frame;
	}

	@Test
	@DisplayName("지연 시간이 지나기 전에는 내보내지 않고, 지나면 받은 순서대로 내보낸다")
	void releasesAfterDelayInOrder() {
		line.offer(frame(1), 0, 1000);
		line.offer(frame(2), 20 * MS, 1020);

		line.drain(799 * MS);
		assertThat(received).isEmpty();

		line.drain(800 * MS);
		assertThat(received).containsExactly(frame(1));

		line.drain(820 * MS);
		assertThat(received).containsExactly(frame(1), frame(2));
	}

	@Test
	@DisplayName("지연이 0이면 바로 내보낸다")
	void zeroDelayPassesThrough() {
		DelayLine direct = new DelayLine(Duration.ZERO, received::add);

		direct.offer(frame(1), 0, 1000);

		assertThat(received).containsExactly(frame(1));
	}

	@Test
	@DisplayName("구간을 지정해 차단하면 그 구간의 아직 안 나간 프레임만 무음이 된다")
	void muteRange() {
		line.offer(frame(1), 0, 1000);
		line.offer(frame(2), 20 * MS, 1020);
		line.offer(frame(3), 40 * MS, 1040);

		int muted = line.mute(1020, 1020);
		line.drain(1000 * MS);

		assertThat(muted).isEqualTo(1);
		assertThat(received).containsExactly(frame(1), new byte[640], frame(3));
	}

	@Test
	@DisplayName("전체 차단은 아직 안 나간 프레임만 무음으로 바꾸고, 이미 나간 프레임은 건드리지 않는다")
	void muteAllPendingOnly() {
		line.offer(frame(1), 0, 1000);
		line.drain(800 * MS);
		line.offer(frame(2), 900 * MS, 1900);

		int muted = line.muteAll();
		line.drain(2000 * MS);

		assertThat(muted).isEqualTo(1);
		assertThat(received).containsExactly(frame(1), new byte[640]);
	}

	@Test
	@DisplayName("같은 프레임을 두 번 차단해도 한 번만 센다")
	void muteTwiceCountsOnce() {
		line.offer(frame(1), 0, 1000);

		assertThat(line.muteAll()).isEqualTo(1);
		assertThat(line.muteAll()).isZero();
	}

	@Test
	@DisplayName("닫으면 남은 프레임을 버리고 새 프레임도 받지 않는다")
	void closeDropsPending() {
		line.offer(frame(1), 0, 1000);
		line.close();
		line.offer(frame(2), 20 * MS, 1020);
		line.drain(10_000 * MS);

		assertThat(received).isEmpty();
		assertThat(line.pendingCount()).isZero();
	}

	@Test
	@DisplayName("한쪽 끝만 주면 그쪽으로 열린 구간을 차단한다")
	void muteOpenRange() {
		line.offer(frame(1), 0, 1000);
		line.offer(frame(2), 20 * MS, 1020);
		line.offer(frame(3), 40 * MS, 1040);

		assertThat(line.mute(1020, Long.MAX_VALUE)).isEqualTo(2);
		line.drain(1000 * MS);

		assertThat(received).containsExactly(frame(1), new byte[640], new byte[640]);
	}

	@Test
	@DisplayName("실시간보다 빠르게 들어와 상한(지연의 2배)을 넘으면 넘친 프레임은 버린다")
	void dropsOverCapacity() {
		int capacity = 800 / 10;
		for (int i = 0; i < capacity + 5; i++) {
			line.offer(frame(1), 0, 1000);
		}

		assertThat(line.pendingCount()).isEqualTo(capacity);
		assertThat(line.droppedCount()).isEqualTo(5);
	}

	@Test
	@DisplayName("지연이 0이어도 닫힌 뒤에는 내보내지 않는다")
	void zeroDelayClosed() {
		DelayLine direct = new DelayLine(Duration.ZERO, received::add);
		direct.close();

		direct.offer(frame(1), 0, 1000);

		assertThat(received).isEmpty();
	}

	@Test
	@DisplayName("차단 유지를 켜면 대기 중인 프레임과 그 뒤에 들어오는 프레임이 모두 무음, 끄면 다시 원래 소리")
	void holdMutesUntilReleased() {
		line.offer(frame(1), 0, 1000);

		assertThat(line.setHold(true)).isEqualTo(1);
		line.offer(frame(2), 20 * MS, 1020);
		line.offer(frame(3), 40 * MS, 1040);
		assertThat(line.setHold(false)).isZero();
		line.offer(frame(4), 60 * MS, 1060);
		line.drain(1000 * MS);

		assertThat(received).containsExactly(new byte[640], new byte[640], new byte[640], frame(4));
		assertThat(line.mutedCount()).isEqualTo(3);
		assertThat(line.isHold()).isFalse();
	}

	@Test
	@DisplayName("지연이 0이어도 차단 유지 중에는 무음으로 내보낸다")
	void holdWithZeroDelay() {
		DelayLine direct = new DelayLine(Duration.ZERO, received::add);
		direct.setHold(true);

		direct.offer(frame(1), 0, 1000);

		assertThat(received).containsExactly(new byte[640]);
	}
}
