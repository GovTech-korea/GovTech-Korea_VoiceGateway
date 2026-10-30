package com.govtech.voicegateway.domain.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

	@ParameterizedTest
	@DisplayName("욕으로 쓰인 '새끼'는 ABUSE (지시어·욕 형용사·부르는 말)")
	@ValueSource(strings = { "야이새끼", "야이새끼야", "나쁜 새끼야", "야 이 새끼야 빨리 해", "미친새끼", "이 새끼들아", "너 같은 새끼야",
			"야 이 새꺄", "이 새꺄 뭐라는 거야", "야 새끼야" })
	void saekki(String text) {
		assertThat(detector.detect(text)).hasValueSatisfying(m -> {
			assertThat(m.riskType()).isEqualTo("ABUSE");
			assertThat(m.keyword()).isEqualTo("새끼");
		});
	}

	@Test
	@DisplayName("'새끼'보다 앞선 규칙(개새끼, 위협)이 먼저 보고된다")
	void saekkiOrder() {
		assertThat(detector.detect("이 개새끼야")).hasValueSatisfying(m -> assertThat(m.keyword()).isEqualTo("개새끼"));
		assertThat(detector.detect("야 이 새끼 죽여버린다")).hasValueSatisfying(m -> assertThat(m.riskType()).isEqualTo("THREAT"));
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
			"고양이 새끼가 길에 버려져 있어요",
			"그 새끼 고양이 좀 구조해 주세요",
			"저 새끼 강아지들 주인을 찾아요",
			"새끼손가락을 다쳤어요",
			"새끼줄로 묶여 있었어요",
			"강아지가 새끼를 낳았는데 그 새끼를 누가 데려갔어요",
			"유기견이 새끼를 낳았는데 이 새끼들 어떻게 해요",
			"이 새끼들 분양하려고요",
			"이 새끼가 아파요",
			"저 새끼 새가 둥지에서 떨어졌어요",
			"그 새끼  고양이",
			"그 새끼",
			"새끼 야",
			"새끼 야생 고양이를 봤어요",
			"개 새로 입양했는데 등록은 어떻게 해요",
			"직장 내 성희롱 신고는 어디로 하나요" })
	void normal(String text) {
		assertThat(detector.detect(text)).isEmpty();
	}

	@Test
	@DisplayName("욕설 목록(abuse-words.txt)을 읽어 모두 찾는다 (파일 형식·로딩 확인)")
	void everyListedWord() {
		List<String> words = List.of(KeywordRiskDetector.loadWords(KeywordRiskDetector.ABUSE_WORDS));

		assertThat(words).hasSizeGreaterThan(1000);
		assertThat(words).allSatisfy(word -> assertThat(detector.detect("아 진짜 " + word + " 왜 이래요")).as(word).isPresent());
	}

	@Test
	@DisplayName("정상 민원 함정 문장(normal-sentences.txt)은 하나도 찾지 않는다")
	void noNormalSentence() {
		List<String> sentences = List.of(KeywordRiskDetector.loadWords("protection/normal-sentences.txt"));

		assertThat(sentences).isNotEmpty();
		assertThat(sentences).allSatisfy(sentence -> assertThat(detector.detect(sentence)).as(sentence).isEmpty());
	}

	@ParameterizedTest
	@DisplayName("실통화 STT 전사에 나온 욕을 찾는다")
	@ValueSource(strings = { "시발새끼", "개색기", "미친 놈", "너는 미친 놈이야", "씨발 새끼", "이 시발샠", "씨발롬아", "개쌔끼야" })
	void realTranscripts(String text) {
		assertThat(detector.detect(text)).isPresent();
	}

	@Test
	@DisplayName("빈 자막은 찾지 않는다")
	void blank() {
		assertThat(detector.detect("")).isEmpty();
		assertThat(detector.detect(null)).isEmpty();
	}
}
