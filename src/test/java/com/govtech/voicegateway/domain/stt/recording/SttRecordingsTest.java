package com.govtech.voicegateway.domain.stt.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.govtech.voicegateway.domain.call.Role;
import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttListener;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.domain.stt.SttStream;

class SttRecordingsTest {

	@TempDir
	Path dir;

	private SttRecordings recordings(SttAudioFormat format) {
		SttProperties properties = new SttProperties(format, dir, null);
		return new SttRecordings(properties, new RecordingSttGateway(properties));
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
		SttProperties properties = new SttProperties(SttAudioFormat.PCM16_16K, null, null);
		SttRecordings recordings = new SttRecordings(properties, new RecordingSttGateway(properties));

		assertThat(recordings.enabled()).isFalse();
		assertThat(recordings.list()).isEmpty();
		assertThat(recordings.openWav("call-1-1000.pcm")).isEmpty();
		assertThat(recordings.delete("call-1-1000.pcm")).isEqualTo(SttRecordings.Deletion.NOT_FOUND);
	}

	@Test
	@DisplayName("기록을 지우면 파일과 목록에서 사라지고, 형식이 틀린 이름·없는 파일·폴더는 지우지 않는다")
	void deleteRecording() throws Exception {
		Files.write(dir.resolve("call-1-1000.pcm"), new byte[4]);
		Files.write(dir.resolve("notes.txt"), new byte[4]);
		Files.createDirectory(dir.resolve("call-2-1000.pcm"));
		SttRecordings recordings = recordings(SttAudioFormat.PCM16_16K);

		assertThat(recordings.delete("call-1-1000.pcm")).isEqualTo(SttRecordings.Deletion.DELETED);
		assertThat(dir.resolve("call-1-1000.pcm")).doesNotExist();
		assertThat(recordings.list()).isEmpty();

		assertThat(recordings.delete("call-1-1000.pcm")).isEqualTo(SttRecordings.Deletion.NOT_FOUND);
		assertThat(recordings.delete("notes.txt")).isEqualTo(SttRecordings.Deletion.NOT_FOUND);
		assertThat(recordings.delete("../notes.txt")).isEqualTo(SttRecordings.Deletion.NOT_FOUND);
		assertThat(recordings.delete("call-2-1000.pcm")).as("폴더").isEqualTo(SttRecordings.Deletion.NOT_FOUND);
		assertThat(dir.resolve("notes.txt")).exists();
		assertThat(dir.resolve("call-2-1000.pcm")).isDirectory();
	}

	@Test
	@DisplayName("통화 중이라 아직 쓰고 있는 기록은 지우지 않고, 통화가 끝나면 지울 수 있다")
	void deleteWhileWriting() throws Exception {
		SttProperties properties = new SttProperties(SttAudioFormat.PCM16_16K, dir, null);
		RecordingSttGateway gateway = new RecordingSttGateway(properties);
		SttRecordings recordings = new SttRecordings(properties, gateway);
		SttStream stream = gateway.open("call-1", SttAudioFormat.PCM16_16K, SttListener.NONE);
		String name;
		try {
			stream.send(new SttChunk("call-1", Role.CITIZEN, 0, 1_000, SttAudioFormat.PCM16_16K, new byte[320]));
			name = await(recordings).name();

			assertThat(recordings.delete(name)).isEqualTo(SttRecordings.Deletion.IN_USE);
			assertThat(dir.resolve(name)).exists();
		} finally {
			stream.close(); // 단언이 실패해도 기록 스레드·파일을 남기지 않는다
		}
		long deadline = System.currentTimeMillis() + 2000;
		while (gateway.isWriting(name) && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertThat(recordings.delete(name)).isEqualTo(SttRecordings.Deletion.DELETED);
	}

	/** 기록 파일이 생길 때까지 기다린다 (파일 쓰기는 다른 스레드) */
	private static SttRecording await(SttRecordings recordings) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 2000;
		while (recordings.list().isEmpty() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		return recordings.list().getFirst();
	}
}
