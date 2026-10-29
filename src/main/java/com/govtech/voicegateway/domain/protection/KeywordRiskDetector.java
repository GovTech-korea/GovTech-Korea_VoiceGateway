package com.govtech.voicegateway.domain.protection;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

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
 * </ul>
 */
@Component
public class KeywordRiskDetector {

	/** 찾은 표현과 그 유형·위험도 (MOMIN 보호 판단 모델의 값) */
	public record Match(String keyword, String riskType, String severity) {
	}

	private record Rule(String keyword, String riskType, String severity, boolean ignoreSpaces) {
	}

	/** 욕설 단어를 품었지만 정상인 말 */
	private static final List<String> SAFE_WORDS = List.of("시발점", "시발역", "시발택시", "질병신", "병신청", "신분증", "등신대");

	// 위협이 욕설보다 위험하므로 먼저 본다
	private static final List<Rule> RULES = Stream.of(
			rules("THREAT", "HIGH", true, "죽여버", "죽여줄", "죽인다", "죽일거", "죽일줄", "가만두지않", "가만안둬", "가만안둘", "불질러",
					"칼로찔", "찔러버"),
			rules("ABUSE", "MEDIUM", false, "씨발", "시발", "씨팔", "시팔", "씨바", "쉬발", "개새끼", "개색기", "병신", "븅신", "좆", "존나",
					"지랄", "미친놈", "미친년", "또라이", "썅", "엿먹어"))
			.flatMap(List::stream)
			.toList();

	private static List<Rule> rules(String riskType, String severity, boolean ignoreSpaces, String... keywords) {
		return Arrays.stream(keywords).map(k -> new Rule(k, riskType, severity, ignoreSpaces)).toList();
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
		return RULES.stream()
				.filter(rule -> (rule.ignoreSpaces() ? compact : spaced).contains(rule.keyword()))
				.findFirst()
				.map(rule -> new Match(rule.keyword(), rule.riskType(), rule.severity()));
	}
}
