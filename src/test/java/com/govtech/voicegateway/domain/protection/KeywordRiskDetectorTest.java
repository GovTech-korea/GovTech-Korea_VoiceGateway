package com.govtech.voicegateway.domain.protection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeywordRiskDetectorTest {

	private final KeywordRiskDetector detector = new KeywordRiskDetector();

	@Test
	@DisplayName("욕설은 ABUSE/MEDIUM")
	void abuse() {
		assertThat(detector.detect("당신 이 개새끼야")).hasValueSatisfying(m -> {
			assertThat(m.riskType()).isEqualTo("ABUSE");
			assertThat(m.severity()).isEqualTo("MEDIUM");
			assertThat(m.keyword()).isEqualTo("개새끼");
		});
		assertThat(detector.detect("아 진짜 시발 왜 이래요")).hasValueSatisfying(m -> assertThat(m.keyword()).isEqualTo("시발"));
	}

	@Test
	@DisplayName("위협은 욕설보다 먼저 THREAT/HIGH로 보고, 띄어서 인식돼도 찾는다")
	void threatFirst() {
		assertThat(detector.detect("이 병신아 내일 찾아가서 가만 두지 않을 거야")).hasValueSatisfying(m -> {
			assertThat(m.riskType()).isEqualTo("THREAT");
			assertThat(m.severity()).isEqualTo("HIGH");
			assertThat(m.keyword()).isEqualTo("가만두지않");
		});
	}

	@ParameterizedTest
	@DisplayName("정상 민원은 찾지 않는다 (단어 경계를 넘거나 욕설을 품은 정상 단어 포함)")
	@ValueSource(strings = {
			"집 근처 가로등이 며칠째 꺼져 있어서 신고하려고요",
			"몇 시 발표예요?",
			"감염병 신고하려고요",
			"질병신고는 어디서 하나요",
			"주민등록증 등 신분증을 가져오래요",
			"시발점이 어디예요",
			"주민센터에 찾아가서 서류를 뗐는데요",
			"내일 찾아간다고 전해 주세요",
			"마감이 닥쳐서 급해요",
			"강아지가 새끼를 낳아서 신고하려고요",
			"개 새로 입양했는데 등록은 어떻게 해요",
			"직장 내 성희롱 신고는 어디로 하나요" })
	void normal(String text) {
		assertThat(detector.detect(text)).isEmpty();
	}

	@Test
	@DisplayName("빈 자막은 찾지 않는다")
	void blank() {
		assertThat(detector.detect("")).isEmpty();
		assertThat(detector.detect(null)).isEmpty();
	}
}
