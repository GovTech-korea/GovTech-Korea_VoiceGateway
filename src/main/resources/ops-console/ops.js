'use strict';

// 운영 상담사 웹. 규격: docs/app-integration.md
// 상담원 WebSocket(/ws/v1/call) 하나로 벨·통화·자막·보호 알림을 받고, 정책·차단은 REST로 바꾼다.
// 음성은 PCM16 little-endian, 16kHz, 모노, 20ms(320샘플 = 640바이트) 프레임.

const SAMPLE_RATE = 16000;
const FRAME_SAMPLES = 320;
const AUDIO_FORMAT = { encoding: 'PCM16LE', sampleRate: SAMPLE_RATE, channels: 1, frameMs: 20 };
const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/v1/call`;
const params = new URLSearchParams(location.search);
// MVP는 상담원 한 명. 다른 상담원 화면과 겹치지 않게 주소의 ?agent= 로 바꿀 수 있다
const AGENT_ID = /^[A-Za-z0-9_-]{1,64}$/.test(params.get('agent') || '') ? params.get('agent') : 'momin-agent';

const $ = (id) => document.getElementById(id);
const body = document.body;

const agent = {
	socket: null,
	state: 'OFFLINE',
	callId: null,
	ringCallId: null,
	hold: false,
	holdPending: false,
	stopMic: null,
	stopTimer: null,
	awayForPolicy: false,
	policy: null,
	lastError: null,
};

// ---------- 오디오 ----------

let audioContext;

function context() {
	if (!audioContext) audioContext = new AudioContext();
	if (audioContext.state === 'suspended') audioContext.resume();
	return audioContext;
}

// 마이크 입력을 16kHz Int16 프레임으로 바꾸는 AudioWorklet (테스트 콘솔과 같은 방식)
const CAPTURE_WORKLET = `
class Capture extends AudioWorkletProcessor {
	constructor() {
		super();
		this.step = sampleRate / ${SAMPLE_RATE};
		this.pos = 0;
		this.prev = 0;
		this.frame = new DataView(new ArrayBuffer(${FRAME_SAMPLES * 2}));
		this.n = 0;
		this.running = true;
		this.port.onmessage = (e) => { if (e.data === 'stop') this.running = false; };
	}
	push(value) {
		const s = Math.max(-1, Math.min(1, value));
		this.frame.setInt16(2 * this.n++, s < 0 ? s * 0x8000 : s * 0x7fff, true);
		if (this.n === ${FRAME_SAMPLES}) {
			this.port.postMessage(this.frame.buffer, [this.frame.buffer]);
			this.frame = new DataView(new ArrayBuffer(${FRAME_SAMPLES * 2}));
			this.n = 0;
		}
	}
	process(inputs) {
		const input = inputs[0] && inputs[0][0];
		if (!input) return this.running;
		const len = input.length;
		const half = Math.max(0, Math.floor(this.step / 2));
		let p = this.pos;
		while (p < len - 1) {
			const i = Math.floor(p);
			const f = p - i;
			const a = i < 0 ? this.prev : this.avg(input, i, half);
			const b = this.avg(input, i + 1, half);
			this.push(a + (b - a) * f);
			p += this.step;
		}
		this.pos = p - len;
		this.prev = input[len - 1];
		return this.running;
	}
	avg(input, i, half) {
		if (half === 0) return input[i];
		let sum = 0, count = 0;
		for (let k = i - half; k <= i + half; k++) {
			if (k >= 0 && k < input.length) { sum += input[k]; count++; }
		}
		return sum / count;
	}
}
registerProcessor('capture', Capture);
`;

let workletReady;

function loadWorklet(ctx) {
	if (!workletReady) {
		const url = URL.createObjectURL(new Blob([CAPTURE_WORKLET], { type: 'application/javascript' }));
		workletReady = ctx.audioWorklet.addModule(url).catch((e) => {
			workletReady = null; // 다음 통화에서 다시 시도한다
			throw e;
		});
	}
	return workletReady;
}

/** 받은 민원인 음성 프레임을 이어서 재생한다 (60ms 여유를 두고 예약) */
const player = {
	nextTime: 0,
	sources: new Set(),
	play(frame) {
		const ctx = context();
		const view = new DataView(frame);
		const buffer = ctx.createBuffer(1, frame.byteLength / 2, SAMPLE_RATE);
		const data = buffer.getChannelData(0);
		for (let i = 0; i < data.length; i++) data[i] = view.getInt16(2 * i, true) / 0x8000;
		const now = ctx.currentTime;
		if (this.nextTime < now + 0.02 || this.nextTime > now + 0.5) this.nextTime = now + 0.06;
		const source = ctx.createBufferSource();
		source.buffer = buffer;
		source.connect(ctx.destination);
		source.onended = () => this.sources.delete(source);
		this.sources.add(source);
		source.start(this.nextTime);
		this.nextTime += buffer.duration;
	},
	/** 예약해 둔 음성을 버린다 (통화 종료) */
	stop() {
		this.sources.forEach((source) => source.stop());
		this.sources.clear();
		this.nextTime = 0;
	},
};

/** 상담원 마이크 → 20ms 프레임. 멈추는 함수를 돌려준다 */
async function startMic(onFrame) {
	const ctx = context();
	const stream = await navigator.mediaDevices.getUserMedia({
		audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
	});
	await loadWorklet(ctx);
	const input = ctx.createMediaStreamSource(stream);
	const node = new AudioWorkletNode(ctx, 'capture');
	node.port.onmessage = (e) => onFrame(e.data);
	input.connect(node);
	return () => {
		node.port.postMessage('stop');
		input.disconnect();
		node.disconnect();
		stream.getTracks().forEach((t) => t.stop());
	};
}

// ---------- 화면 공통 ----------

function pad(n) {
	return String(n).padStart(2, '0');
}

/** ms → 00:00:00 */
function hms(ms) {
	const sec = Math.max(0, Math.floor(ms / 1000));
	return `${pad(Math.floor(sec / 3600))}:${pad(Math.floor(sec / 60) % 60)}:${pad(sec % 60)}`;
}

function tickClock() {
	const d = new Date();
	$('clock').textContent = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const STATE_TEXT = { OFFLINE: '연결 중', READY: '상담대기', AWAY: '이석중', RINGING: '전화 수신', IN_CALL: '상담중' };

function setAgentState(state) {
	agent.state = state;
	body.dataset.agent = state;
	$('state-text').textContent = STATE_TEXT[state] || state;
	// 끝난 통화의 '통화 종료 · 사유'는 다음 전화가 올 때까지 남겨 둔다 (상태는 위쪽 상태 버튼에 보인다)
	if (!agent.callId && body.dataset.call !== 'ended') {
		$('call-state-text').textContent = state === 'AWAY' ? '이석중' : '상담 대기';
	}
}

function showView(view) {
	if (view === 'policy' && agent.state === 'RINGING') return; // 벨을 놓치지 않게 받거나 끝날 때까지 통화 화면에 둔다
	if (body.dataset.view === view) return;
	body.dataset.view = view;
	// 정책을 손보는 동안은 이석중으로 둔다 (전화가 오지 않게). 돌아오면 다시 상담대기
	if (view === 'policy' && agent.state === 'READY') {
		agent.awayForPolicy = true;
		send({ type: 'status', state: 'AWAY' });
	} else if (view === 'call' && agent.awayForPolicy) {
		agent.awayForPolicy = false;
		if (agent.state === 'AWAY') send({ type: 'status', state: 'READY' });
	}
	if (view === 'policy') loadPolicyForm();
}

// ---------- WebSocket ----------

function send(msg) {
	const ws = agent.socket;
	if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
}

function connect() {
	const ws = new WebSocket(WS_URL);
	ws.binaryType = 'arraybuffer';
	agent.socket = ws;
	ws.onopen = () => {
		agent.lastError = null;
		send({ type: 'register', role: 'AGENT', agentId: AGENT_ID, audio: AUDIO_FORMAT });
	};
	ws.onmessage = (e) => {
		if (typeof e.data === 'string') {
			onMessage(JSON.parse(e.data));
		} else if (agent.callId) {
			player.play(e.data);
		}
	};
	ws.onclose = () => {
		endCall();
		hideRing();
		setAgentState('OFFLINE');
		const error = agent.lastError;
		if (error === 'DUPLICATE_AGENT') {
			$('state-text').textContent = '다른 화면에서 접속 중';
			setTimeout(connect, 15000); // 다른 화면이 닫히면 다시 붙는다
		} else if (error === 'INVALID_MESSAGE' || error === 'UNSUPPORTED_AUDIO') {
			$('state-text').textContent = '접속 거부됨';
		} else {
			setTimeout(connect, 3000); // 서버 재시작·네트워크 끊김 뒤 다시 붙는다
		}
	};
}

function onMessage(msg) {
	switch (msg.type) {
		case 'registered':
			setAgentState(msg.state);
			if (body.dataset.view === 'policy' && msg.state === 'READY') {
				agent.awayForPolicy = true;
				send({ type: 'status', state: 'AWAY' });
			}
			break;
		case 'status-changed':
			setAgentState(msg.state);
			// 정책 화면에 있는 동안 통화가 끝나 서버가 상담대기로 돌렸으면 다시 이석중으로 둔다
			if (msg.state === 'READY' && msg.reason === 'CALL_ENDED' && body.dataset.view === 'policy') {
				agent.awayForPolicy = true;
				send({ type: 'status', state: 'AWAY' });
			}
			break;
		case 'ringing':
			setAgentState('RINGING');
			showRing(msg.callId);
			break;
		case 'ring-cancelled':
			hideRing();
			break;
		case 'connected':
			startCall(msg.callId);
			break;
		case 'call-ended':
			if (msg.callId === agent.callId) endCall(msg.reason);
			break;
		case 'transcript':
			if (msg.callId === agent.callId) $('caption').textContent = msg.text;
			break;
		case 'mute-hold-changed':
			if (msg.callId === agent.callId) setHold(msg.hold);
			break;
		case 'protection-state':
			if (msg.callId === agent.callId) showCounters(msg);
			break;
		case 'protection-event':
			if (msg.callId === agent.callId) upsertEvent(msg);
			break;
		case 'emotion':
			if (msg.callId === agent.callId) showEmotion(msg.score, msg.agitated);
			break;
		case 'error':
			console.warn('게이트웨이 오류', msg.code, msg.message);
			agent.lastError = msg.code;
			break;
		default:
			break;
	}
}

// ---------- 전화 수신·통화 ----------

function showRing(callId) {
	agent.ringCallId = callId;
	$('ring-call').textContent = callId;
	$('ring').hidden = false;
	agent.awayForPolicy = false;
	showView('call');
}

function hideRing() {
	agent.ringCallId = null;
	$('ring').hidden = true;
}

async function accept() {
	if (!agent.ringCallId) return;
	context(); // 클릭 안에서 오디오를 깨워 둔다 (브라우저 자동 재생 제한)
	send({ type: 'accept', callId: agent.ringCallId });
}

function startCall(callId) {
	hideRing();
	agent.callId = callId;
	body.dataset.call = 'active';
	setAgentState('IN_CALL');
	$('call-state-text').textContent = '통화 중';
	$('caller-id').textContent = callId;
	$('caption').textContent = '';
	$('events').innerHTML = '';
	$('events-empty').hidden = false;
	showEmotion(null, false);
	setHold(false);
	$('hold-btn').disabled = false;
	$('hangup').disabled = false;
	const startedAt = Date.now();
	const timer = setInterval(() => ($('call-timer').textContent = hms(Date.now() - startedAt)), 500);
	agent.stopTimer = () => clearInterval(timer);
	loadPolicySummary();
	startMic((frame) => {
		const ws = agent.socket;
		if (agent.callId && ws && ws.readyState === WebSocket.OPEN) ws.send(frame);
	}).then((stop) => {
		if (agent.callId === callId) agent.stopMic = stop;
		else stop();
	}).catch((e) => {
		console.warn('마이크를 열 수 없음', e);
		if (agent.callId === callId) $('caption').textContent = '마이크를 쓸 수 없어 상담원 목소리가 민원인에게 가지 않습니다 (브라우저 마이크 권한·HTTPS 확인)';
	});
}

const END_TEXT = { HANGUP_AGENT: '상담원 종료', HANGUP_CITIZEN: '민원인 종료', DISCONNECTED: '연결 끊김', POLICY_TERMINATED: '보호정책 종료' };

function endCall(reason) {
	if (!agent.callId) return;
	agent.callId = null;
	player.stop();
	if (agent.stopMic) agent.stopMic();
	agent.stopMic = null;
	if (agent.stopTimer) agent.stopTimer();
	agent.stopTimer = null;
	body.dataset.call = 'ended';
	$('call-state-text').textContent = reason ? `통화 종료 · ${END_TEXT[reason] || reason}` : '통화 종료';
	$('hold-btn').disabled = true;
	$('hangup').disabled = true;
	setHold(false);
	$('volume-word').textContent = '-';
	$('volume-pct').textContent = '-';
	$('volume-bar').style.width = '0';
	loadPolicySummary(); // 통화 중 바뀐 정책은 다음 통화부터라 끝난 뒤에 갱신한다
}

function setHold(on) {
	agent.hold = on;
	body.dataset.hold = on ? 'on' : 'off';
	$('protect-text').textContent = on ? '음성 차단 중' : '정상';
	$('hold-btn-text').textContent = on ? '오탐지 (수동 해제)' : '수동 음소거';
	if (agent.callId) {
		$('volume-word').textContent = on ? '0' : '정상';
		$('volume-pct').textContent = on ? '0%' : '100%';
		$('volume-bar').style.width = on ? '0' : '100%';
	}
}

/** 상담원이 직접 막거나 푼다. 푸는 것은 오탐지 해제로 처리된다 (그 차단 중 센 폭언이 횟수에서 빠짐) */
async function toggleHold() {
	if (!agent.callId || agent.holdPending) return;
	const callId = agent.callId;
	const on = !agent.hold;
	agent.holdPending = true;
	$('hold-btn').disabled = true;
	try {
		const res = await fetch(`/api/v1/calls/${encodeURIComponent(callId)}/mute-hold`, {
			method: 'PUT',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ hold: on }),
		});
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		// 화면 상태는 서버가 보내는 mute-hold-changed로 맞춘다
	} catch (e) {
		console.warn('차단 변경 실패', e);
	} finally {
		agent.holdPending = false;
		if (agent.callId === callId) $('hold-btn').disabled = false;
	}
}

// ---------- 카운터·감정·전사 ----------

function showCounters(s) {
	$('abuse-count').textContent = s.abuseCount;
	$('abuse-limit').textContent = s.abuseLimit;
	$('after-count').textContent = s.afterNoticeCount;
	$('after-limit').textContent = s.afterNoticeLimit;
	$('agitation-sec').textContent = s.agitationSeconds;
	$('agitation-limit').textContent = s.agitationLimitSeconds;
	$('c-before').classList.toggle('alert', s.abuseCount > 0);
	$('c-after').classList.toggle('alert', s.afterNoticeCount > 0);
	$('c-agitation').classList.toggle('alert', s.agitationSeconds > 0);
}

function showEmotion(score, agitated) {
	const tag = $('emotion-tag');
	if (score == null) {
		$('emotion-score').textContent = '-';
		tag.textContent = '대기';
		tag.className = 'tag';
		moveGauge(0);
		return;
	}
	$('emotion-score').textContent = `${score}%`;
	tag.textContent = agitated ? '격양' : '안정';
	tag.className = 'tag ' + (agitated ? 'agitated' : 'calm');
	moveGauge(score / 100);
}

/** 반원 게이지 점 위치 (0~1) */
function moveGauge(ratio) {
	const angle = Math.PI * (1 - Math.max(0, Math.min(1, ratio)));
	$('gauge-dot').setAttribute('cx', (100 + 80 * Math.cos(angle)).toFixed(1));
	$('gauge-dot').setAttribute('cy', (100 - 80 * Math.sin(angle)).toFixed(1));
}

const EVENT_TITLE = { ABUSE: '폭언 확정', AGITATION: '감정 격양', FALSE_POSITIVE: '오탐지 해제' };
const EVENT_ICON = { ABUSE: '', AGITATION: '#i-gear', FALSE_POSITIVE: '#i-check' };

/** 같은 eventId가 다시 오면 그 줄을 덮어쓴다 (폭언 → 오탐지 해제) */
function upsertEvent(e) {
	const list = $('events');
	let item = list.querySelector(`[data-event="${CSS.escape(e.eventId)}"]`);
	if (!item) {
		item = document.createElement('li');
		item.dataset.event = e.eventId;
		list.appendChild(item);
	}
	item.className = 'event ' + e.kind;
	item.innerHTML = '';
	const head = document.createElement('header');
	const kind = document.createElement('span');
	kind.className = 'kind';
	if (EVENT_ICON[e.kind]) {
		kind.innerHTML = `<svg><use href="${EVENT_ICON[e.kind]}"/></svg>`;
	}
	kind.append(EVENT_TITLE[e.kind] || e.kind);
	const time = document.createElement('span');
	time.className = 'time';
	time.textContent = `${hms(e.startMs)} ~ ${hms(e.endMs)}`;
	head.append(kind, time);
	const text = document.createElement('div');
	text.className = 'text';
	const span = document.createElement('span');
	text.append(span);
	if (e.kind === 'ABUSE') {
		// 폭언은 가려 두고, 상담원이 원할 때만 본다
		const masked = '*'.repeat(Math.min(18, Math.max(6, (e.text || '').length)));
		span.textContent = masked;
		const reveal = document.createElement('button');
		reveal.className = 'reveal';
		reveal.innerHTML = '<svg><use href="#i-eye"/></svg>내용 보기';
		reveal.onclick = () => {
			const shown = span.textContent !== masked;
			span.textContent = shown ? masked : e.text || '';
			reveal.lastChild.textContent = shown ? '내용 보기' : '가리기';
		};
		text.append(reveal);
	} else {
		span.textContent = e.text || '';
	}
	item.append(head, text);
	$('events-empty').hidden = true;
	item.scrollIntoView({ block: 'nearest' });
}

// ---------- 정책 ----------

async function fetchPolicy() {
	const res = await fetch('/api/v1/policy');
	if (!res.ok) throw new Error(`HTTP ${res.status}`);
	agent.policy = await res.json();
	return agent.policy;
}

/** 정책 요약 한 줄 (강조 부분은 굵게) */
function renderSummary(p) {
	const el = $('policy-summary');
	const bold = (text) => {
		const b = document.createElement('b');
		b.textContent = text;
		return b;
	};
	el.textContent = '';
	if (p.afterNotice === 'IMMEDIATE') {
		el.append('정책 : 폭언 ', bold(`${Number(p.abuseLimit)}회`), ' 시 ', bold('즉시 종료'));
	} else {
		el.append('정책 : 폭언 안내 후 ', bold(`${Number(p.afterNoticeLimit)}회 추가 시 종료`));
	}
}

async function loadPolicySummary() {
	try {
		const p = await fetchPolicy();
		if (agent.callId) return; // 진행 중인 통화는 연결 때의 정책을 끝까지 쓴다
		renderSummary(p);
		if (body.dataset.call === 'ended') return; // 끝난 통화의 카운터는 다음 통화가 연결될 때까지 남겨 둔다
		// 통화 전에는 카운터 분모를 지금 정책으로 보여 준다 (통화가 연결되면 protection-state가 덮어쓴다)
		showCounters({ abuseCount: 0, abuseLimit: p.abuseLimit, afterNoticeCount: 0,
			afterNoticeLimit: p.afterNotice === 'IMMEDIATE' ? 1 : p.afterNoticeLimit,
			agitationSeconds: 0, agitationLimitSeconds: p.agitationSeconds });
	} catch {
		$('policy-summary').textContent = '정책을 불러오지 못했습니다';
	}
}

function fillForm(p) {
	$('p-abuse').value = p.abuseLimit;
	$('p-after').value = p.afterNoticeLimit;
	$('p-agitation').value = p.agitationSeconds;
	document.querySelector(`input[name="after"][value="${p.afterNotice}"]`).checked = true;
}

async function loadPolicyForm() {
	try {
		fillForm(await fetchPolicy());
		saveMessage('');
	} catch (e) {
		saveMessage('정책을 불러오지 못했습니다', true);
	}
}

function saveMessage(text, error) {
	const el = $('save-msg');
	el.textContent = text;
	el.className = 'save-msg' + (error ? ' err' : '');
}

async function savePolicy() {
	const policy = {
		abuseLimit: Number($('p-abuse').value),
		afterNotice: document.querySelector('input[name="after"]:checked').value,
		afterNoticeLimit: Number($('p-after').value),
		agitationSeconds: Number($('p-agitation').value),
	};
	try {
		const res = await fetch('/api/v1/policy', {
			method: 'PUT',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(policy),
		});
		if (!res.ok) throw new Error(res.status === 400 ? '값 범위를 확인해 주세요' : `HTTP ${res.status}`);
		fillForm((agent.policy = await res.json()));
		saveMessage('저장했습니다. 다음 통화부터 적용됩니다.');
		loadPolicySummary();
	} catch (e) {
		saveMessage(`저장 실패: ${e.message}`, true);
	}
}

async function resetPolicy() {
	try {
		const res = await fetch('/api/v1/policy', { method: 'DELETE' });
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		fillForm(await fetchPolicy());
		saveMessage('기본값으로 되돌렸습니다.');
		loadPolicySummary();
	} catch (e) {
		saveMessage(`초기화 실패: ${e.message}`, true);
	}
}

function step(id, delta) {
	const input = $(id);
	const value = Number(input.value || 0) + delta;
	input.value = Math.max(Number(input.min), Math.min(Number(input.max), value));
}

// ---------- 안내 멘트 미리듣기 ----------

let preview = null;

async function loadAnnouncements() {
	const list = $('anns');
	try {
		const res = await fetch('/api/v1/announcements');
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		for (const a of await res.json()) {
			const item = document.createElement('li');
			item.className = 'ann';
			const chip = document.createElement('span');
			chip.className = 'chip';
			chip.textContent = a.title;
			const text = document.createElement('span');
			text.className = 'ann-text';
			text.textContent = a.text;
			text.title = `${a.text} (${a.when})`;
			const button = document.createElement('button');
			button.innerHTML = '<svg><use href="#i-play"/></svg><span>미리듣기</span>';
			button.onclick = () => togglePreview(a.audioUrl, button);
			item.append(chip, text, button);
			list.append(item);
		}
	} catch {
		list.innerHTML = '<li class="empty">안내 멘트를 불러오지 못했습니다</li>';
	}
}

function togglePreview(url, button) {
	const same = preview && preview.button === button;
	stopPreview();
	if (same) return;
	const audio = new Audio(url);
	preview = { audio, button };
	button.querySelector('use').setAttribute('href', '#i-stop');
	button.querySelector('span').textContent = '정지';
	audio.onended = stopPreview;
	audio.play().catch(stopPreview);
}

function stopPreview() {
	if (!preview) return;
	preview.audio.pause();
	preview.button.querySelector('use').setAttribute('href', '#i-play');
	preview.button.querySelector('span').textContent = '미리듣기';
	preview = null;
}

// ---------- 시작 ----------

document.querySelectorAll('[data-go]').forEach((b) => (b.onclick = () => showView(b.dataset.go)));
document.querySelectorAll('[data-step]').forEach((b) => {
	const [id, delta] = b.dataset.step.split(':');
	b.onclick = (e) => {
		e.preventDefault();
		step(id, Number(delta));
	};
});
$('accept').onclick = accept;
$('hangup').onclick = () => agent.callId && send({ type: 'hangup', callId: agent.callId });
$('hold-btn').onclick = toggleHold;
$('state-pill').onclick = () => {
	if (agent.state === 'READY') send({ type: 'status', state: 'AWAY' });
	else if (agent.state === 'AWAY') {
		agent.awayForPolicy = false;
		send({ type: 'status', state: 'READY' });
	}
};
$('p-save').onclick = savePolicy;
$('p-reset').onclick = resetPolicy;
if (params.get('org')) $('org-name').textContent = params.get('org');
if (params.get('name')) $('agent-name').textContent = params.get('name');

setHold(false);
tickClock();
setInterval(tickClock, 10_000);
loadPolicySummary();
loadAnnouncements();
connect();
if (location.hash === '#policy') showView('policy');
