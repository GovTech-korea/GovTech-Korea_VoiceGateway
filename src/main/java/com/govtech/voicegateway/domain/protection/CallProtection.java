package com.govtech.voicegateway.domain.protection;

import java.util.ArrayList;
import java.util.List;

/**
 * 통화 하나의 보호 정책 집행 (docs/app-integration.md "보호 정책").
 * 폭언과 감정 격양을 세고, 정책에 닿으면 어떤 안내 멘트를 틀지({@link Action}) 알려 준다.
 * 시각은 모두 통화 연결부터 잰 ms다. 멘트 재생과 통화 종료는 부르는 쪽이 한다.
 */
public final class CallProtection {

	private final String callId;
	private final ProtectionPolicy policy;
	private int abuseCount;
	private int afterNoticeCount;
	private boolean noticed;
	private boolean terminating;
	private int eventSequence;
	/** 이어지고 있는 격양 구간. 시작이 없으면(-1) 격양 중이 아니다 */
	private long agitationStartMs = -1;
	private long agitationEndMs;
	private long agitationMs;
	private final List<String> agitationTexts = new ArrayList<>();
	/** 지금 차단 유지를 일으킨 폭언. 상담원이 오탐지로 풀면 횟수에서 뺀다 */
	private final List<Counted> holdAbuses = new ArrayList<>();

	public CallProtection(String callId, ProtectionPolicy policy) {
		this.callId = callId;
		this.policy = policy;
	}

	/** 욕설이 감지됐다 (폭언 확정) */
	public synchronized Result abuse(long startMs, long endMs, String text, String keyword) {
		if (terminating) {
			return Result.NONE;
		}
		Event event = new Event(nextEventId(), EventKind.ABUSE, startMs, endMs, text, keyword);
		boolean afterNotice = noticed;
		Action action = count();
		holdAbuses.add(new Counted(event, afterNotice));
		return new Result(List.of(event), action);
	}

	/**
	 * 민원인 발화 하나의 감정 판단. 격양이 이어진 시간이 정책 시간에 닿으면 폭언 1회로 세고 0부터 다시 잰다.
	 * 격양이 아닌 발화가 오면 이어진 시간을 0으로 돌린다.
	 */
	public synchronized Result emotion(boolean agitated, long startMs, long endMs, String text) {
		if (terminating) {
			return Result.NONE;
		}
		if (!agitated) {
			resetAgitation();
			return Result.NONE;
		}
		if (agitationStartMs < 0) {
			agitationStartMs = startMs;
		}
		agitationEndMs = endMs;
		agitationMs += Math.max(0, endMs - startMs);
		agitationTexts.add(text);
		if (agitationMs < policy.agitationSeconds() * 1000L) {
			return Result.NONE;
		}
		Event event = new Event(nextEventId(), EventKind.AGITATION, agitationStartMs, agitationEndMs,
				String.join(" ", agitationTexts), null);
		resetAgitation();
		return new Result(List.of(event), count());
	}

	/**
	 * 상담원이 '오탐지 (수동 해제)'로 차단을 풀었다. 차단 중에 확정한 폭언을 오탐지로 바꾸고 횟수에서 뺀다.
	 * 이미 나간 안내 멘트는 되돌리지 않는다.
	 */
	public synchronized Result falsePositive() {
		if (terminating) {
			holdAbuses.clear();
			return Result.NONE;
		}
		List<Event> events = new ArrayList<>();
		for (Counted counted : holdAbuses) {
			if (counted.afterNotice()) {
				afterNoticeCount = Math.max(0, afterNoticeCount - 1);
			} else {
				abuseCount = Math.max(0, abuseCount - 1);
			}
			Event abuse = counted.event();
			events.add(new Event(abuse.eventId(), EventKind.FALSE_POSITIVE, abuse.startMs(), abuse.endMs(), abuse.text(),
					abuse.keyword()));
		}
		holdAbuses.clear();
		return new Result(events, Action.NONE);
	}

	/** 상담원이 아닌 쪽(STT)이 차단을 풀었다. 그때까지의 폭언은 오탐지로 보지 않는다 */
	public synchronized void holdReleased() {
		holdAbuses.clear();
	}

	/** 지금까지 격양이 이어진 시간 (ms) */
	public synchronized long agitationMs() {
		return agitationMs;
	}

	/** '세션 종료' 멘트를 틀기로 해서 곧 통화를 끊는다 */
	public synchronized boolean isTerminating() {
		return terminating;
	}

	public synchronized State state() {
		return new State(callId, abuseCount, policy.abuseLimit(), afterNoticeCount, policy.effectiveAfterNoticeLimit(),
				(int) (agitationMs / 1000), policy.agitationSeconds());
	}

	private Action count() {
		if (!noticed) {
			abuseCount++;
			if (abuseCount >= policy.abuseLimit()) {
				noticed = true;
				return Action.AFTER_NOTICE;
			}
			return Action.NONE;
		}
		afterNoticeCount++;
		if (afterNoticeCount >= policy.effectiveAfterNoticeLimit()) {
			terminating = true;
			return Action.SESSION_END;
		}
		return Action.NONE;
	}

	private void resetAgitation() {
		agitationStartMs = -1;
		agitationMs = 0;
		agitationTexts.clear();
	}

	private String nextEventId() {
		return callId + "-e" + (++eventSequence);
	}

	/** 폭언을 센 뒤 민원인에게 틀 안내 멘트 ('폭언 안내'는 통화 연결 때 나가므로 여기 없다) */
	public enum Action {
		NONE,
		/** 폭언 후 안내: 안내 전 폭언이 허용 횟수에 닿음. 이후 폭언은 안내 후 횟수로 센다 */
		AFTER_NOTICE,
		/** 세션 종료: 멘트 뒤 통화를 끊는다 */
		SESSION_END
	}

	public enum EventKind {
		ABUSE,
		AGITATION,
		FALSE_POSITIVE
	}

	/** 상담원 화면 '음소거 구간 전사' 목록의 한 줄. 같은 eventId가 다시 오면 그 줄을 덮어쓴다 */
	public record Event(String eventId, EventKind kind, long startMs, long endMs, String text, String keyword) {
	}

	public record State(String callId, int abuseCount, int abuseLimit, int afterNoticeCount, int afterNoticeLimit,
			int agitationSeconds, int agitationLimitSeconds) {
	}

	public record Result(List<Event> events, Action action) {
		static final Result NONE = new Result(List.of(), Action.NONE);
	}

	private record Counted(Event event, boolean afterNotice) {
	}
}
