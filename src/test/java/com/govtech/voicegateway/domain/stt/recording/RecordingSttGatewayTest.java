package com.govtech.voicegateway.domain.stt.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.govtech.voicegateway.domain.call.Role;
import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.domain.stt.SttStream;

class RecordingSttGatewayTest {

	@TempDir
	Path dir;

	private static SttChunk chunk(long sequence, int value) {
		byte[] pcm = new byte[640];
		Arrays.fill(pcm, (byte) value);
		return new SttChunk("call-1", Role.CITIZEN, sequence, 1_000 + sequence * 20, SttAudioFormat.PCM16_16K, pcm);
	}

	@Test
	@DisplayName("dump-dir을 주면 받은 바이트를 순서대로 <callId>-<시각>.pcm에 쓴다")
	void dumpsToFile() throws Exception {
		RecordingSttGateway gateway = new RecordingSttGateway(new SttProperties(SttAudioFormat.PCM16_16K, dir));
		SttStream stream = gateway.open("call-1", SttAudioFormat.PCM16_16K);

		stream.send(chunk(0, 1));
		stream.send(chunk(1, 2));
		stream.close();

		Path file = awaitFile(1280);
		assertThat(file.getFileName().toString()).matches("call-1-\\d+\\.pcm");
		byte[] written = Files.readAllBytes(file);
		assertThat(written).hasSize(1280);
		assertThat(written[0]).isEqualTo((byte) 1);
		assertThat(written[640]).isEqualTo((byte) 2);
	}

	@Test
	@DisplayName("같은 callId로 다시 열어도 이전 기록을 덮어쓰지 않는다")
	void keepsPreviousDump() throws Exception {
		RecordingSttGateway gateway = new RecordingSttGateway(new SttProperties(SttAudioFormat.PCM16_16K, dir));
		SttStream first = gateway.open("call-1", SttAudioFormat.PCM16_16K);
		first.send(chunk(0, 1));
		first.close();
		awaitFile(640);
		Thread.sleep(2); // 파일 이름의 ms가 달라지게

		SttStream second = gateway.open("call-1", SttAudioFormat.PCM16_16K);
		second.send(chunk(0, 2));
		second.close();

		long deadline = System.nanoTime() + 5_000_000_000L;
		while (System.nanoTime() < deadline) {
			try (var files = Files.list(dir)) {
				if (files.filter(f -> sizeOf(f) == 640).count() == 2) {
					return;
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("기록 파일 두 개가 생기지 않음");
	}

	private Path awaitFile(long size) throws Exception {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (System.nanoTime() < deadline) {
			try (var files = Files.list(dir)) {
				var found = files.filter(f -> sizeOf(f) >= size).findFirst();
				if (found.isPresent()) {
					return found.get();
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("기록 파일이 생기지 않음");
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (java.io.IOException e) {
			return -1;
		}
	}

	@Test
	@DisplayName("dump-dir이 없으면 파일을 만들지 않는다")
	void noDump() throws Exception {
		RecordingSttGateway gateway = new RecordingSttGateway(new SttProperties(SttAudioFormat.PCM16_16K, null));
		SttStream stream = gateway.open("call-1", SttAudioFormat.PCM16_16K);

		stream.send(chunk(0, 1));
		stream.close();

		try (var files = Files.list(dir)) {
			assertThat(files).isEmpty();
		}
	}
}
