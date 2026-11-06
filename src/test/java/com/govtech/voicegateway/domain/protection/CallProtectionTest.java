package com.govtech.voicegateway.domain.protection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.govtech.voicegateway.domain.protection.CallProtection.Action;
import com.govtech.voicegateway.domain.protection.CallProtection.Event;
import com.govtech.voicegateway.domain.protection.CallProtection.EventKind;
import com.govtech.voicegateway.domain.protection.ProtectionPolicy.AfterNotice;

class CallProtectionTest {

	private static CallProtection protection(int abuseLimit, AfterNotice afterNotice, int afterNoticeLimit, int agitationSeconds) {
		return new CallProtection("call-1", new ProtectionPolicy(abuseLimit, afterNotice, afterNoticeLimit, agitationSeconds));
	}

	@Test
	@DisplayName("폭언마다 보호 기록이 생기고, 허용 횟수째에 폭언 후 안내, 안내 후 폭언이 추가 횟수에 닿으면 세션 종료")
	void countsThroughPolicy() {
		CallProtection protection = protection(2, AfterNotice.COUNT, 2, 5);

		CallProtection.Result first = protection.abuse(30_000, 36_000, "당신 개새끼야", "개새끼");
		assertThat(first.action()).isEqualTo(Action.NONE);
		assertThat(first.events()).containsExactly(
				new Event("call-1-e1", EventKind.ABUSE, 30_000, 36_000, "당신 개새끼야", "개새끼"));
		assertThat(protection.abuse(40_000, 41_000, "시발", "시발").action()).isEqualTo(Action.AFTER_NOTICE);
		assertThat(protection.abuse(50_000, 51_000, "병신", "병신").action()).isEqualTo(Action.NONE);
		assertThat(protection.abuse(60_000, 61_000, "개새끼", "개새끼").action()).isEqualTo(Action.SESSION_END);

		assertThat(protection.state()).isEqualTo(new CallProtection.State("call-1", 2, 2, 2, 2, 0, 5));
		assertThat(protection.isTerminating()).isTrue();
		assertThat(protection.abuse(70_000, 71_000, "시발", "시발")).isEqualTo(CallProtection.Result.NONE);
	}

	@Test
		@DisplayName("IMMEDIATE면 안내 전 폭언이 허용 횟수에 닿을 때 폭언 후 안내 없이 바로 세션 종료이고, 그 뒤 폭언은 세지 않는다")
	void immediate() {
		CallProtection protection = protection(2, AfterNotice.IMMEDIATE, 5, 5);

		assertThat(protection.abuse(0, 0, "시발", "시발").action()).isEqualTo(Action.NONE);
		assertThat(protection.abuse(0, 0, "시발", "시발").action()).isEqualTo(Action.SESSION_END);
		assertThat(protection.isTerminating()).isTrue();
		assertThat(protection.abuse(0, 0, "시발", "시발")).isEqualTo(CallProtection.Result.NONE);
		assertThat(protection.state().afterNoticeLimit()).isEqualTo(1);
	}

	@Test
	@DisplayName("폭언 의심 해제(dismissed)는 FALSE_POSITIVE 기록만 만들고 횟수는 그대로 둔다")
	void dismissed() {
		CallProtection protection = protection(5, AfterNotice.COUNT, 2, 5);

		CallProtection.Result result = protection.dismissed(1_000, 2_000, "그 사람이 개자식이라고 했어요", "개자식");

		assertThat(result.events()).singleElement().satisfies(event -> {
			assertThat(event.kind()).isEqualTo(EventKind.FALSE_POSITIVE);
			assertThat(event.eventId()).isEqualTo("call-1-e1");
		});
		assertThat(result.action()).isEqualTo(Action.NONE);
		assertThat(protection.state().abuseCount()).isZero();
	}

	@Test
	@DisplayName("오탐지 해제는 차단 중 확정한 폭언을 같은 eventId의 FALSE_POSITIVE로 바꾸고 횟수에서 뺀다")
	void falsePositive() {
		CallProtection protection = protection(5, AfterNotice.COUNT, 2, 5);
		protection.abuse(70_000, 74_000, "제가 미친 듯이 화를 낸 게 아니라", "미친");

		CallProtection.Result released = protection.falsePositive();

		assertThat(released.events()).containsExactly(
				new Event("call-1-e1", EventKind.FALSE_POSITIVE, 70_000, 74_000, "제가 미친 듯이 화를 낸 게 아니라", "미친"));
		assertThat(protection.state().abuseCount()).isZero();
		assertThat(protection.falsePositive().events()).isEmpty(); // 이미 푼 것은 다시 바꾸지 않는다
	}

	@Test
	@DisplayName("안내 뒤의 폭언을 오탐지로 풀면 안내 후 횟수에서 뺀다")
	void falsePositiveAfterNotice() {
		CallProtection protection = protection(1, AfterNotice.COUNT, 3, 5);
		protection.abuse(0, 0, "시발", "시발");
		protection.falsePositive();
		protection.abuse(0, 0, "병신", "병신");

		protection.falsePositive();

		assertThat(protection.state().abuseCount()).isZero();
		assertThat(protection.state().afterNoticeCount()).isZero();
	}

	@Test
	@DisplayName("STT가 차단을 풀면 그 전 폭언은 오탐지로 바꾸지 않는다")
	void holdReleasedBySystem() {
		CallProtection protection = protection(5, AfterNotice.COUNT, 2, 5);
		protection.abuse(0, 0, "시발", "시발");

		protection.holdReleased();

		assertThat(protection.falsePositive().events()).isEmpty();
		assertThat(protection.state().abuseCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("격양이 정책 시간만큼 이어지면 폭언 1회로 세고 지속 시간을 0부터 다시 잰다")
	void agitationCountsAsAbuse() {
		CallProtection protection = protection(5, AfterNotice.COUNT, 2, 5);

		assertThat(protection.emotion(true, 45_000, 48_000, "도대체 언제까지").events()).isEmpty();
		assertThat(protection.state().agitationSeconds()).isEqualTo(3);
		CallProtection.Result result = protection.emotion(true, 48_500, 52_000, "기다리게 하는 거예요!");

		assertThat(result.events()).containsExactly(new Event("call-1-e1", EventKind.AGITATION, 45_000, 52_000,
				"도대체 언제까지 기다리게 하는 거예요!", null));
		assertThat(protection.state().abuseCount()).isEqualTo(1);
		assertThat(protection.state().agitationSeconds()).isZero();
	}

	@Test
	@DisplayName("격양이 아닌 발화가 오면 이어진 격양 시간을 0으로 돌린다")
	void calmResetsAgitation() {
		CallProtection protection = protection(5, AfterNotice.COUNT, 2, 5);
		protection.emotion(true, 0, 4_000, "왜 이렇게 늦어요");

		protection.emotion(false, 5_000, 6_000, "네 알겠습니다");
		protection.emotion(true, 7_000, 9_000, "빨리 해 주세요");

		assertThat(protection.state().agitationSeconds()).isEqualTo(2);
		assertThat(protection.state().abuseCount()).isZero();
	}

	@Test
	@DisplayName("격양으로 센 폭언은 오탐지 해제 대상이 아니다")
	void agitationIsNotFalsePositive() {
		CallProtection protection = protection(5, AfterNotice.COUNT, 2, 3);
		protection.emotion(true, 0, 3_000, "도대체!");

		assertThat(protection.falsePositive().events()).isEmpty();
		assertThat(protection.state().abuseCount()).isEqualTo(1);
	}
}
