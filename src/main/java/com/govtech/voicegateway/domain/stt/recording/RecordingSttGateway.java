package com.govtech.voicegateway.domain.stt.recording;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.govtech.voicegateway.domain.stt.SttAudioFormat;
import com.govtech.voicegateway.domain.stt.SttChunk;
import com.govtech.voicegateway.domain.stt.SttGateway;
import com.govtech.voicegateway.domain.stt.SttProperties;
import com.govtech.voicegateway.domain.stt.SttStream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * STT 엔진이 정해지기 전까지 쓰는 가짜 구현. 받은 조각 수·바이트 수를 세고,
 * {@code voicegateway.stt.dump-dir}을 지정하면 STT가 받았을 바이트를 그대로 파일에 쓴다.
 * 파일 쓰기는 스트림마다 가상 스레드 하나에서 해서 음성 수신 스레드를 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingSttGateway implements SttGateway {

	/** 쓰기가 밀려 이만큼(약 5초) 쌓이면 이후 조각은 파일에 쓰지 않는다 */
	static final int MAX_PENDING = 250;

	private final SttProperties properties;

	@Override
	public SttStream open(String callId, SttAudioFormat format) {
		// 같은 callId로 다시 걸어도 이전 기록을 덮어쓰지 않게 시작 시각을 붙인다
		Path file = properties.dumpDir() == null ? null
				: properties.dumpDir().toAbsolutePath().resolve(callId + "-" + System.currentTimeMillis() + ".pcm");
		log.info("STT 스트림 시작: {} ({}{})", callId, format, file == null ? "" : ", 기록 " + file);
		return new RecordingStream(callId, format, file);
	}

	private static final class RecordingStream implements SttStream {

		private final String callId;
		private final SttAudioFormat format;
		private final Path file;
		private final ExecutorService writer;
		private final AtomicInteger pending = new AtomicInteger();
		private final AtomicLong chunks = new AtomicLong();
		private final AtomicLong bytes = new AtomicLong();
		private final AtomicLong skipped = new AtomicLong();
		private OutputStream out;
		/** 파일을 열지 못하면 이 스트림은 기록을 그만둔다 (조각마다 다시 시도하며 로그를 쏟지 않게) */
		private boolean broken;

		private RecordingStream(String callId, SttAudioFormat format, Path file) {
			this.callId = callId;
			this.format = format;
			this.file = file;
			this.writer = file == null ? null
					: Executors.newSingleThreadExecutor(Thread.ofVirtual().name("stt-dump-" + callId).factory());
		}

		@Override
		public void send(SttChunk chunk) {
			chunks.incrementAndGet();
			bytes.addAndGet(chunk.pcm().length);
			if (writer == null) {
				return;
			}
			if (pending.incrementAndGet() > MAX_PENDING) {
				pending.decrementAndGet();
				skipped.incrementAndGet();
				return;
			}
			writer.execute(() -> {
				try {
					write(chunk.pcm());
				} finally {
					pending.decrementAndGet();
				}
			});
		}

		private void write(byte[] pcm) {
			if (broken) {
				skipped.incrementAndGet();
				return;
			}
			try {
				if (out == null) {
					Files.createDirectories(file.getParent());
					out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				}
				out.write(pcm);
			} catch (IOException e) {
				broken = true;
				skipped.incrementAndGet();
				log.warn("STT 기록 중단: {} {}", file, e.toString());
			}
		}

		@Override
		public void close() {
			if (writer != null) {
				writer.execute(this::closeFile);
				writer.shutdown();
			}
			log.info("STT 스트림 종료: {} ({}) 조각 {}개, {}바이트{}", callId, format, chunks.get(), bytes.get(),
					skipped.get() == 0 ? "" : ", 기록 못 한 조각 " + skipped.get() + "개");
		}

		private void closeFile() {
			if (out != null) {
				try {
					out.close();
				} catch (IOException e) {
					log.warn("STT 기록 파일 닫기 실패: {} {}", file, e.getMessage());
				}
			}
		}
	}
}
