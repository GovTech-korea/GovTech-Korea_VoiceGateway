package com.govtech.voicegateway.domain.audio.api;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.govtech.voicegateway.domain.audio.AnnouncementKind;
import com.govtech.voicegateway.domain.audio.Announcements;
import com.govtech.voicegateway.domain.audio.Wav;

import lombok.RequiredArgsConstructor;

/**
 * 안내 멘트 목록과 미리듣기 음성. 정책설정 화면(앱·웹)이 부른다. 멘트는 게이트웨이가 가진 고정 3종이다.
 */
@CrossOrigin(origins = "${voicegateway.policy.allowed-origins:*}")
@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

	private static final MediaType AUDIO_WAV = MediaType.parseMediaType("audio/wav");

	private final Announcements announcements;

	@GetMapping
	public List<AnnouncementResponse> list() {
		return Arrays.stream(AnnouncementKind.values())
				.map(kind -> AnnouncementResponse.from(kind, announcements.get(kind)))
				.toList();
	}

	/** 멘트 음성 (WAV, 16kHz mono 16비트). 민원인에게 나가는 것과 같은 음성이다 */
	@GetMapping("/{kind}/audio")
	public ResponseEntity<byte[]> audio(@PathVariable String kind) {
		AnnouncementKind found = Arrays.stream(AnnouncementKind.values())
				.filter(k -> k.name().equals(kind))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 안내 멘트입니다: " + kind));
		return ResponseEntity.ok()
				.contentType(AUDIO_WAV)
				.cacheControl(CacheControl.noCache())
				.body(Wav.fromPcm16k(announcements.get(found).pcm()));
	}
}
