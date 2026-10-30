package com.govtech.voicegateway.domain.protection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 1차 규칙 필터: STT 자막에서 욕설·위협 표현을 찾는다 (보고서 처리 흐름의 "규칙·키워드 필터").
 * 문맥(누구를 향했는지 등)은 판단하지 않는다. 그건 뒤에 붙을 LLM 판단의 몫이고, 그 결과가 오면 이 결과를 대신한다.
 * <p>
 * 오탐(정상 민원인을 차단)이 가장 위험한 실패이므로 정밀도를 우선한다.
 * <ul>
 * <li>욕설 단어는 띄어쓰기를 지우지 않고 찾는다. 지우면 "몇 시 발표", "질병 신고"처럼 단어 경계를 넘어 걸린다</li>
 * <li>위협은 뜻이 분명한 구절만, 띄어쓰기를 지우고 찾는다 ("가만 두지 않", "죽여 버" 등)</li>
 * <li>정상 민원에도 나오는 말("찾아가서", "닥쳐서", "꺼져 있어요", 성희롱 신고 등)은 넣지 않는다</li>
 * <li>욕설을 품은 정상 단어(시발점, 질병신고, 신분증 등)는 먼저 지우고 본다</li>
 * <li>"새끼"는 동물·손가락에도 쓰므로 욕으로만 쓰이는 형태(야 이 새끼, 나쁜 새끼, 새끼야)일 때만 찾는다</li>
 * </ul>
 */
@Component
public class KeywordRiskDetector {

	/** 찾은 표현과 그 유형·위험도 (MOMIN 보호 판단 모델의 값) */
	public record Match(String keyword, String riskType, String severity) {
	}

	/** pattern이 없으면 keyword 글자가 들어 있는지만 본다 */
	private record Rule(String keyword, Pattern pattern, String riskType, String severity, boolean ignoreSpaces) {

		boolean matches(String text) {
			return pattern == null ? text.contains(keyword) : pattern.matcher(text).find();
		}
	}

	/** 욕설 단어를 품었지만 정상인 말 */
	private static final List<String> SAFE_WORDS = List.of("시발점", "시발역", "시발택시", "질병신", "병신청", "신분증", "등신대",
			"감염병", "전염병");

	/**
	 * 욕으로만 쓰이는 "새끼": 부르는 말(야 이 새끼, 새끼야, 새끼들아, 새꺄)과 욕 형용사(나쁜·미친 새끼).
	 * "이/그 새끼"는 동물 민원("이 새끼들 분양하려고요")에도 나오고 중간 결과가 "그 새끼"에서 잘리기도 해서 보지 않는다.
	 */
	private static final String SAEKKI_FORMS = "(?:새끼|새꺄|색기|새키)";
	private static final Pattern SAEKKI = Pattern.compile(
			"(?:(?<![가-힣])야\\s?이?\\s?" + SAEKKI_FORMS
					+ "|(?:나쁜|미친|썩을|망할|못된|더러운|빌어먹을)\\s?" + SAEKKI_FORMS
					+ "|새꺄"
					+ "|새끼(?:야|들아)(?![가-힣]))"
					+ "(?!\\s*(?:고양이|강아지|돼지|오리|손가락|발가락|줄))");

	// 위협이 욕설보다 위험하므로 먼저 본다. 욕설 목록 파일은 위 규칙이 놓친 것만 잡는다
	private final List<Rule> rules = Stream.of(
			rules("THREAT", "HIGH", true, "죽여버", "죽여줄", "죽인다", "죽일거", "죽일줄", "가만두지않", "가만안둬", "가만안둘", "불질러",
					"칼로찔", "찔러버"),
			rules("ABUSE", "MEDIUM", false, "씨발", "시발", "씨팔", "시팔", "씨바", "쉬발", "개새끼", "개색기", "병신", "븅신", "좆", "존나",
					"지랄", "미친놈", "미친년", "또라이", "썅", "엿먹어"),
			List.of(new Rule("새끼", SAEKKI, "ABUSE", "MEDIUM", false)),
			rules("ABUSE", "MEDIUM", false, loadWords(ABUSE_WORDS)))
			.flatMap(List::stream)
			.toList();

	/** 변형 철자·띄어쓰기를 조합한 욕설 목록 (한 줄에 하나, #은 주석) */
	static final String ABUSE_WORDS = "protection/abuse-words.txt";

	static String[] loadWords(String path) {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.distinct()
					.toArray(String[]::new);
		} catch (IOException e) {
			throw new UncheckedIOException("욕설 목록을 읽을 수 없습니다: " + path, e);
		}
	}

	private static List<Rule> rules(String riskType, String severity, boolean ignoreSpaces, String... keywords) {
		return Arrays.stream(keywords).map(k -> new Rule(k, null, riskType, severity, ignoreSpaces)).toList();
	}

	public Optional<Match> detect(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String cleaned = text;
		for (String safe : SAFE_WORDS) {
			cleaned = cleaned.replace(safe, " ");
		}
		String compact = cleaned.replaceAll("\\s+", "");
		String spaced = cleaned;
		return rules.stream()
				.filter(rule -> rule.matches(rule.ignoreSpaces() ? compact : spaced))
				.findFirst()
				.map(rule -> new Match(rule.keyword(), rule.riskType(), rule.severity()));
	}
}
