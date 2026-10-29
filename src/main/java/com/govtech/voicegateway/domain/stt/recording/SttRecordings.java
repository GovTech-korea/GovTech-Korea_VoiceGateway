package com.govtech.voicegateway.domain.stt.recording;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.govtech.voicegateway.domain.stt.SttProperties;

import lombok.RequiredArgsConstructor;

/**
 * {@link RecordingSttGateway}가 남긴 기록을 찾아 WAV로 읽는다 (들어 보기용).
 * 기록 파일에는 형식 정보가 없어서 지금 설정한 {@code voicegateway.stt.format}으로 해석한다.
 */
@Component
@RequiredArgsConstructor
public class SttRecordings {

	/** 통화 ID 규칙([A-Za-z0-9_-]{1,64}) + 시작 시각(long 범위 안). 이 형식이 아니면 경로로 쓰지 않는다 */
	private static final Pattern NAME = Pattern.compile("([A-Za-z0-9_-]{1,64})-(\\d{1,18})\\.pcm");

	private final SttProperties properties;
	private final RecordingSttGateway gateway;

	/** 삭제 결과 */
	public enum Deletion {
		DELETED, NOT_FOUND, IN_USE
	}

	public boolean enabled() {
		return properties.dumpDir() != null;
	}

	/** 최근 기록부터 */
	public List<SttRecording> list() {
		if (!enabled() || !Files.isDirectory(dir())) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir())) {
			return files.map(this::toRecording)
					.flatMap(Optional::stream)
					.sorted(Comparator.comparingLong(SttRecording::startedAtEpochMs).reversed())
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** WAV로 읽을 스트림. 헤더 뒤에 파일을 그대로 이어 읽어 메모리에 통째로 올리지 않는다 */
	public record Wav(long length, InputStream stream) {
	}

	/** @return 이름이 형식에 맞지 않거나 일반 파일이 없으면 비어 있다. 스트림은 받는 쪽이 닫는다 */
	public Optional<Wav> openWav(String name) {
		if (!enabled() || !NAME.matcher(name).matches()) {
			return Optional.empty();
		}
		return findFile(name).map(this::open);
	}

	/**
	 * 기록 하나를 지운다. 통화 중이라 아직 쓰고 있는 기록은 지우지 않는다.
	 * 이름은 {@link #openWav}와 같은 규칙으로 검사하고, 실제 폴더 목록에서 고른 파일만 지운다.
	 */
	public Deletion delete(String name) {
		if (!enabled() || !NAME.matcher(name).matches()) {
			return Deletion.NOT_FOUND;
		}
		if (gateway.isWriting(name)) {
			return Deletion.IN_USE;
		}
		Optional<Path> file = findFile(name);
		if (file.isEmpty()) {
			return Deletion.NOT_FOUND;
		}
		try {
			return Files.deleteIfExists(file.get()) ? Deletion.DELETED : Deletion.NOT_FOUND;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * 요청한 이름으로 경로를 만들지 않고, 기록 폴더의 실제 파일 중 이름이 같은 것을 고른다.
	 * 이름 검사를 빠져나가는 입력이 있어도 폴더 밖 파일에 닿을 수 없다.
	 */
	private Optional<Path> findFile(String name) {
		if (!Files.isDirectory(dir())) {
			return Optional.empty();
		}
		try (Stream<Path> files = Files.list(dir())) {
			return files.filter(file -> file.getFileName().toString().equals(name))
					.filter(Files::isRegularFile)
					.findFirst();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Wav open(Path file) {
		try {
			long pcmLength = Files.size(file);
			byte[] header = wavHeader(pcmLength, properties.format().sampleRate());
			InputStream stream = new SequenceInputStream(new ByteArrayInputStream(header), Files.newInputStream(file));
			return new Wav(header.length + pcmLength, stream);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Path dir() {
		return properties.dumpDir().toAbsolutePath();
	}

	private Optional<SttRecording> toRecording(Path file) {
		Matcher matcher = NAME.matcher(file.getFileName().toString());
		if (!matcher.matches()) {
			return Optional.empty();
		}
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			long bytes = Files.size(file);
			long durationMs = bytes * 1000 / (properties.format().sampleRate() * 2L);
			return Optional.of(new SttRecording(file.getFileName().toString(), matcher.group(1),
					Long.parseLong(matcher.group(2)), bytes, durationMs));
		} catch (IOException e) {
			return Optional.empty();
		}
	}

	/** 헤더 없는 PCM16 모노 앞에 붙일 44바이트 WAV 헤더 */
	static byte[] wavHeader(long pcmLength, int sampleRate) {
		int length = (int) Math.min(pcmLength, Integer.MAX_VALUE - 36);
		return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
				.put("RIFF".getBytes()).putInt(36 + length).put("WAVE".getBytes())
				.put("fmt ".getBytes()).putInt(16)
				.putShort((short) 1) // PCM
				.putShort((short) 1) // 모노
				.putInt(sampleRate)
				.putInt(sampleRate * 2) // 초당 바이트
				.putShort((short) 2) // 샘플당 바이트
				.putShort((short) 16) // 샘플당 비트
				.put("data".getBytes()).putInt(length)
				.array();
	}
}
