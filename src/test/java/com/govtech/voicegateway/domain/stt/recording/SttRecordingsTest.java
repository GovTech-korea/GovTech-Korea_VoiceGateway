package com.govtech.voicegateway.domain.stt.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttProperties;

class SttRecordingsTest {

	@TempDir
	Path dir;

	private SttRecordings recordings(SttAudioFormat format) {
		return new SttRecordings(new SttProperties(format, dir, null));
	}

	@Test
	@DisplayName("기록 파일을 최근 것부터 통화 ID·시작 시각·길이와 함께 나열하고, 형식이 다른 파일은 뺀다")
	void list() throws Exception {
		Files.write(dir.resolve("call-1-1000.pcm"), new byte[32_000]); // 16kHz 1초
		Files.write(dir.resolve("call-2-2000.pcm"), new byte[16_000]);
		Files.write(dir.resolve("notes.txt"), new byte[10]);
		Files.write(dir.resolve("call-3-9999999999999999999.pcm"), new byte[10]);

		assertThat(recordings(SttAudioFormat.PCM16_16K).list())
				.containsExactly(
						new SttRecording("call-2-2000.pcm", "call-2", 2000, 16_000, 500),
						new SttRecording("call-1-1000.pcm", "call-1", 1000, 32_000, 1000));
	}

	@Test
	@DisplayName("WAV로 읽으면 44바이트 헤더(PCM, 모노, 설정한 샘플링, 16비트)가 앞에 붙는다")
	void readWav() throws Exception {
		byte[] pcm = { 1, 2, 3, 4 };
		Files.write(dir.resolve("call-1-1000.pcm"), pcm);

		SttRecordings.Wav opened = recordings(SttAudioFormat.PCM16_8K).openWav("call-1-1000.pcm").orElseThrow();
		byte[] wav;
		try (var stream = opened.stream()) {
			wav = stream.readAllBytes();
		}
		assertThat(opened.length()).isEqualTo(wav.length);

		ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
		assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
		assertThat(header.getInt(4)).isEqualTo(36 + pcm.length);
		assertThat(new String(wav, 8, 8)).isEqualTo("WAVEfmt ");
		assertThat(header.getShort(20)).isEqualTo((short) 1);
		assertThat(header.getShort(22)).isEqualTo((short) 1);
		assertThat(header.getInt(24)).isEqualTo(8000);
		assertThat(header.getInt(28)).isEqualTo(16000);
		assertThat(header.getShort(34)).isEqualTo((short) 16);
		assertThat(new String(wav, 36, 4)).isEqualTo("data");
		assertThat(header.getInt(40)).isEqualTo(pcm.length);
		assertThat(wav).hasSize(44 + pcm.length).endsWith(pcm);
	}

	@Test
	@DisplayName("규칙에 맞지 않는 이름(경로 조작 포함)이나 없는 파일은 읽지 않는다")
	void rejectsBadNames() throws Exception {
		Files.write(dir.resolve("call-1-1000.pcm"), new byte[4]);
		SttRecordings recordings = recordings(SttAudioFormat.PCM16_16K);

		Files.createDirectory(dir.resolve("call-2-1000.pcm"));

		assertThat(recordings.openWav("../call-1-1000.pcm")).isEmpty();
		assertThat(recordings.openWav("call-1-1000.txt")).isEmpty();
		assertThat(recordings.openWav("call-9-1000.pcm")).isEmpty();
		assertThat(recordings.openWav("call-2-1000.pcm")).as("폴더").isEmpty();
		assertThat(recordings.openWav("call-1-9999999999999999999.pcm")).as("long 범위 밖").isEmpty();
	}

	@Test
	@DisplayName("dump-dir을 설정하지 않으면 비어 있다")
	void disabled() {
		SttRecordings recordings = new SttRecordings(new SttProperties(SttAudioFormat.PCM16_16K, null, null));

		assertThat(recordings.enabled()).isFalse();
		assertThat(recordings.list()).isEmpty();
		assertThat(recordings.openWav("call-1-1000.pcm")).isEmpty();
	}
}
