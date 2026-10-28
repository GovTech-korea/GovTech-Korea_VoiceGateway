'use strict';

// Voice Gateway 테스트 콘솔. 규격: docs/app-integration.md
// 음성은 PCM16 little-endian, 16kHz, 모노, 20ms(320샘플 = 640바이트) 프레임.

const SAMPLE_RATE = 16000;
const FRAME_SAMPLES = 320;
const AUDIO_FORMAT = { encoding: 'PCM16LE', sampleRate: SAMPLE_RATE, channels: 1, frameMs: 20 };
const WS_URL = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/v1/call`;
const TIMELINE_MS = 10000;

const $ = (id) => document.getElementById(id);

// 같은 스크립트를 통합 콘솔(console.html)과 역할별 화면(agent.html, citizen.html)이 함께 쓴다.
// 페이지에 있는 칸만 동작시킨다.
const HAS_AGENT = !!$('agent-panel');
const HAS_CITIZEN = !!$('citizen-panel');
const HAS_STATUS = !!$('status-panel');

/** 페이지에 있을 때만 글자를 바꾼다 */
function setText(id, text) {
	const el = $(id);
	if (el) el.textContent = text;
}

/** 통화 시간 표시 (mm:ss). 시작하면 멈추는 함수를 돌려준다 */
function startCallTimer(id) {
	const start = performance.now();
	const tick = () => {
		const sec = Math.floor((performance.now() - start) / 1000);
		setText(id, `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`);
	};
	tick();
	const timer = setInterval(tick, 500);
	return () => {
		clearInterval(timer);
		setText(id, '');
	};
}

// ---------- 공통: 오디오 ----------

let audioContext;

function context() {
	if (!audioContext) {
		audioContext = new AudioContext();
	}
	if (audioContext.state === 'suspended') {
		audioContext.resume();
	}
	return audioContext;
}

// 마이크 입력을 16kHz Int16 프레임으로 바꾸는 AudioWorklet.
// 브라우저 기본 샘플링(보통 48kHz)에서 구간 평균으로 저역을 거른 뒤 선형 보간으로 16kHz를 뽑는다.
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
		this.frame.setInt16(2 * this.n++, s < 0 ? s * 0x8000 : s * 0x7fff, true); // little-endian
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
		workletReady = ctx.audioWorklet.addModule(url);
	}
	return workletReady;
}

/** PCM16 little-endian 프레임 → 샘플 (플랫폼 바이트 순서와 무관하게) */
function samplesOf(frame) {
	const view = new DataView(frame);
	const samples = new Int16Array(frame.byteLength / 2);
	for (let i = 0; i < samples.length; i++) {
		samples[i] = view.getInt16(2 * i, true);
	}
	return samples;
}

/** 샘플 → PCM16 little-endian 프레임 */
function frameOf(samples) {
	const view = new DataView(new ArrayBuffer(samples.length * 2));
	for (let i = 0; i < samples.length; i++) {
		view.setInt16(2 * i, samples[i], true);
	}
	return view.buffer;
}

/** 소리 크기 0~1 (RMS) */
function level(frame) {
	const samples = samplesOf(frame);
	let sum = 0;
	for (let i = 0; i < samples.length; i++) {
		sum += samples[i] * samples[i];
	}
	return Math.min(1, Math.sqrt(sum / samples.length) / 8000);
}

/** 받은 프레임을 이어서 재생한다. 네트워크 흔들림을 흡수하려고 60ms 여유를 두고 예약한다. */
class Player {
	constructor() {
		this.nextTime = 0;
	}

	play(frame) {
		const ctx = context();
		const samples = samplesOf(frame);
		const buffer = ctx.createBuffer(1, samples.length, SAMPLE_RATE);
		const data = buffer.getChannelData(0);
		for (let i = 0; i < samples.length; i++) {
			data[i] = samples[i] / 0x8000;
		}
		const now = ctx.currentTime;
		if (this.nextTime < now + 0.02 || this.nextTime > now + 0.5) {
			this.nextTime = now + 0.06;
		}
		const source = ctx.createBufferSource();
		source.buffer = buffer;
		source.connect(ctx.destination);
		source.start(this.nextTime);
		this.nextTime += buffer.duration;
	}

	reset() {
		this.nextTime = 0;
	}
}

/** 마이크 → 20ms 프레임 */
async function micSource(onFrame) {
	const ctx = context();
	const stream = await navigator.mediaDevices.getUserMedia({
		audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
	});
	let input;
	let node;
	try {
		await loadWorklet(ctx);
		input = ctx.createMediaStreamSource(stream);
		node = new AudioWorkletNode(ctx, 'capture');
	} catch (e) {
		stream.getTracks().forEach((t) => t.stop());
		throw e;
	}
	node.port.onmessage = (e) => onFrame(e.data);
	input.connect(node);
	return () => {
		node.port.postMessage('stop');
		input.disconnect();
		node.disconnect();
		stream.getTracks().forEach((t) => t.stop());
	};
}

/** 미리 만든 16kHz 샘플을 실시간 속도(20ms마다 한 프레임)로 흘려 보낸다. loop면 반복 */
function pcmSource(samples, onFrame, loop) {
	const start = performance.now();
	let sent = 0;
	const timer = setInterval(() => {
		const due = Math.floor((performance.now() - start) / 20);
		if (due - sent > 10) {
			sent = due; // 백그라운드 탭에서 타이머가 늦춰졌으면 밀린 분량을 한꺼번에 보내지 않고 건너뛴다
		}
		while (sent <= due) {
			let offset = sent * FRAME_SAMPLES;
			if (offset + FRAME_SAMPLES > samples.length) {
				if (!loop) {
					clearInterval(timer);
					return;
				}
				offset %= samples.length; // 길이는 프레임 배수
			}
			onFrame(frameOf(samples.subarray(offset, offset + FRAME_SAMPLES)));
			sent++;
		}
	}, 10);
	return () => clearInterval(timer);
}

/** 0.5초 울리고 0.5초 쉬는 440Hz 톤 (1초 반복) */
function toneSamples() {
	const samples = new Int16Array(SAMPLE_RATE);
	for (let i = 0; i < SAMPLE_RATE / 2; i++) {
		samples[i] = Math.round(0.3 * 0x7fff * Math.sin((2 * Math.PI * 440 * i) / SAMPLE_RATE));
	}
	return samples;
}

/** 오디오 파일을 16kHz 모노 Int16으로 */
async function fileSamples(file) {
	const decoded = await context().decodeAudioData(await file.arrayBuffer());
	const length = Math.ceil(decoded.duration * SAMPLE_RATE);
	const offline = new OfflineAudioContext(1, length, SAMPLE_RATE);
	const source = offline.createBufferSource();
	source.buffer = decoded;
	source.connect(offline.destination);
	source.start();
	const rendered = (await offline.startRendering()).getChannelData(0);
	// 프레임(320샘플) 배수로 0을 채워 마지막 프레임도 640바이트가 되게 한다
	const samples = new Int16Array(Math.max(1, Math.ceil(rendered.length / FRAME_SAMPLES)) * FRAME_SAMPLES);
	for (let i = 0; i < rendered.length; i++) {
		const s = Math.max(-1, Math.min(1, rendered[i]));
		samples[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
	}
	return samples;
}

// ---------- 공통: 연결·로그 ----------

function logTo(list, kind, text) {
	const item = document.createElement('li');
	item.className = kind;
	const time = clock(new Date());
	item.textContent = `${time} ${kind === 'in' ? '←' : kind === 'out' ? '→' : '·'} ${text}`;
	list.appendChild(item);
	while (list.children.length > 200) {
		list.removeChild(list.firstChild);
	}
	list.scrollTop = list.scrollHeight;
}

function clock(date) {
	return [date.getHours(), date.getMinutes(), date.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':');
}

/** WebSocket 하나. 텍스트는 JSON으로 파싱해 onMessage, 바이너리는 onAudio */
function openSocket(log, onMessage, onAudio, onClose) {
	const ws = new WebSocket(WS_URL);
	ws.binaryType = 'arraybuffer';
	ws.onmessage = (e) => {
		if (typeof e.data === 'string') {
			let msg;
			try {
				msg = JSON.parse(e.data);
			} catch {
				logTo(log, 'err', `JSON이 아닌 메시지: ${e.data}`);
				return;
			}
			logTo(log, msg.type === 'error' ? 'err' : 'in', e.data);
			onMessage(msg);
		} else {
			onAudio(e.data);
		}
	};
	ws.onclose = (e) => {
		logTo(log, 'sys', `연결 종료 (code ${e.code}${e.reason ? ', ' + e.reason : ''})`);
		onClose();
	};
	ws.onerror = () => logTo(log, 'err', '연결 오류');
	return {
		ws,
		send(msg) {
			if (ws.readyState === WebSocket.OPEN) {
				const text = JSON.stringify(msg);
				ws.send(text);
				logTo(log, 'out', text);
			}
		},
		sendAudio(frame) {
			if (ws.readyState === WebSocket.OPEN) {
				ws.send(frame);
			}
		},
		opened: new Promise((resolve, reject) => {
			ws.addEventListener('open', resolve, { once: true });
			ws.addEventListener('error', reject, { once: true });
		}),
	};
}

function setBadge(el, text, kind) {
	el.textContent = text;
	el.className = 'badge' + (kind ? ' ' + kind : '');
}

function randomId(prefix) {
	return `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
}

// ---------- 타임라인 ----------

const timeline = {
	sent: [], // [ms, level]
	heard: [],
	mutes: [], // ms
	add(list, value) {
		const now = performance.now();
		list.push([now, value]);
		while (list.length && list[0][0] < now - TIMELINE_MS) list.shift();
	},
};

function drawTimeline() {
	const canvas = $('timeline');
	const ratio = window.devicePixelRatio || 1;
	const width = canvas.clientWidth;
	const height = canvas.clientHeight;
	if (canvas.width !== width * ratio || canvas.height !== height * ratio) {
		canvas.width = width * ratio;
		canvas.height = height * ratio;
	}
	const g = canvas.getContext('2d');
	g.setTransform(ratio, 0, 0, ratio, 0, 0);
	g.clearRect(0, 0, width, height);
	const css = getComputedStyle(document.documentElement);
	const color = (name) => css.getPropertyValue(name).trim();
	const now = performance.now();
	const x = (t) => width - ((now - t) / TIMELINE_MS) * width;
	const labelWidth = 0;
	// 페이지에 있는 역할의 줄만 그린다 (민원인 화면은 보낸 소리, 상담원 화면은 들은 소리)
	const rows = [];
	if (HAS_CITIZEN) rows.push({ list: timeline.sent, color: '--sent', label: '민원인이 보낸 소리', muteShift: -1 });
	if (HAS_AGENT) rows.push({ list: timeline.heard, color: '--heard', label: '상담원이 들은 소리', muteShift: 0 });
	const rowHeight = (height - 18) / Math.max(1, rows.length);

	// 1초 눈금
	g.strokeStyle = color('--line');
	g.fillStyle = color('--muted');
	g.font = '11px system-ui';
	g.lineWidth = 1;
	const step = width / (TIMELINE_MS / 1000) < 45 ? 2 : 1; // 좁은 화면에서는 2초 간격
	for (let s = 0; s <= TIMELINE_MS / 1000; s += step) {
		const px = width - (s * 1000 / TIMELINE_MS) * width;
		g.beginPath();
		g.moveTo(px, 0);
		g.lineTo(px, height - 18);
		g.stroke();
		g.fillText(s === 0 ? '지금' : `-${s}s`, Math.min(px + 3, width - 26), height - 4);
	}

	// 차단 표시: 누르기 전 지연 시간 동안 보낸 소리(아직 버퍼에 있던 것)가, 누른 뒤 지연 시간 동안 상담원에게 무음으로 간다
	g.fillStyle = color('--mute');
	const delay = state.delayMs || 0;
	const bandWidth = Math.max(2, (delay / TIMELINE_MS) * width);
	timeline.mutes = timeline.mutes.filter((t) => t > now - TIMELINE_MS - delay);
	for (const t of timeline.mutes) {
		rows.forEach((row, i) => g.fillRect(x(t + row.muteShift * delay), i * rowHeight, bandWidth, rowHeight));
	}

	const drawRow = (list, top, colorName, label) => {
		g.fillStyle = color(colorName);
		const barWidth = Math.max(1, (20 / TIMELINE_MS) * width);
		for (const [t, v] of list) {
			const h = Math.max(1, v * (rowHeight - 8));
			g.fillRect(x(t), top + (rowHeight - h) / 2, barWidth, h);
		}
		g.fillStyle = color('--muted');
		g.fillText(label, 4 + labelWidth, top + 12);
	};
	rows.forEach((row, i) => drawRow(row.list, i * rowHeight, row.color, row.label));
	requestAnimationFrame(drawTimeline);
}

const state = { delayMs: 0 };

// ---------- 상담원 ----------

const agent = {
	conn: null,
	callId: null,
	ringTimer: null,
	stopMic: null,
	micGeneration: 0,
	stopTimer: null,
	player: new Player(),
	log: $('agent-log'),
};

function agentUi() {
	const connected = !!agent.conn;
	$('agent-connect').disabled = connected;
	$('agent-disconnect').disabled = !connected;
	$('agent-id').disabled = connected;
	$('agent-ready').disabled = !connected || agent.state === 'RINGING' || agent.state === 'IN_CALL';
	$('agent-away').disabled = $('agent-ready').disabled;
	$('agent-ring').hidden = agent.state !== 'RINGING';
	$('agent-incall').hidden = agent.state !== 'IN_CALL';
	const labels = { READY: ['대기 중', 'ok'], AWAY: ['자리 비움', 'warn'], RINGING: ['벨 울림', 'warn'], IN_CALL: ['통화 중', 'active'] };
	const [text, kind] = connected ? labels[agent.state] || ['접속 중', ''] : ['연결 안 됨', ''];
	setBadge($('agent-state'), text, kind);
}

async function agentConnect() {
	const agentId = $('agent-id').value.trim();
	if (!/^[A-Za-z0-9_-]{1,64}$/.test(agentId)) {
		logTo(agent.log, 'err', '상담원 ID는 영문·숫자·-·_ 1~64자');
		return;
	}
	context();
	agent.conn = openSocket(agent.log, agentMessage, agentAudio, agentClosed);
	agentUi();
	try {
		await agent.conn.opened;
		agent.conn.send({ type: 'register', role: 'AGENT', agentId, audio: AUDIO_FORMAT });
	} catch {
		logTo(agent.log, 'err', '접속 실패');
	}
}

function agentMessage(msg) {
	switch (msg.type) {
		case 'registered':
		case 'status-changed':
			agent.state = msg.state;
			if (msg.state !== 'IN_CALL') stopAgentCall();
			break;
		case 'ringing':
			agent.state = 'RINGING';
			agent.callId = msg.callId;
			$('ring-call').textContent = msg.callId;
			startCountdown(msg.timeoutMs);
			break;
		case 'ring-cancelled':
			clearInterval(agent.ringTimer);
			agent.callId = null;
			if (agent.state === 'RINGING') agent.state = 'READY';
			break;
		case 'connected':
			clearInterval(agent.ringTimer);
			agent.state = 'IN_CALL';
			state.delayMs = msg.delayMs;
			$('agent-call').textContent = msg.callId;
			$('agent-delay').textContent = `${msg.delayMs}ms`;
			agent.player.reset();
			agent.stopTimer = startCallTimer('agent-timer');
			if ($('agent-mic').checked) startAgentMic();
			break;
		case 'call-ended':
			stopAgentCall();
			agent.callId = null;
			break;
	}
	agentUi();
}

function agentAudio(frame) {
	agent.player.play(frame);
	const v = level(frame);
	$('agent-meter').style.width = `${v * 100}%`;
	timeline.add(timeline.heard, v);
}

function agentClosed() {
	clearInterval(agent.ringTimer);
	stopAgentCall();
	agent.conn = null;
	agent.state = null;
	agentUi();
}

function startCountdown(timeoutMs) {
	clearInterval(agent.ringTimer);
	const end = performance.now() + timeoutMs;
	const tick = () => {
		$('ring-countdown').textContent = `${Math.max(0, Math.ceil((end - performance.now()) / 1000))}초`;
	};
	tick();
	agent.ringTimer = setInterval(tick, 250);
}

// 마이크는 권한 확인 등으로 늦게 열린다. 그 사이 통화가 끝나거나 다시 켜면 세대 번호가 바뀌고, 늦게 열린 마이크는 바로 닫는다
async function startAgentMic() {
	stopAgentMic();
	const generation = ++agent.micGeneration;
	try {
		const stop = await micSource((frame) => agent.conn && agent.conn.sendAudio(frame));
		if (generation !== agent.micGeneration) {
			stop();
			return;
		}
		agent.stopMic = stop;
	} catch (e) {
		logTo(agent.log, 'err', `마이크 사용 불가: ${e.message}`);
	}
}

function stopAgentMic() {
	agent.micGeneration++;
	if (agent.stopMic) {
		agent.stopMic();
		agent.stopMic = null;
	}
}

function stopAgentCall() {
	if (agent.stopTimer) {
		agent.stopTimer();
		agent.stopTimer = null;
	}
	stopAgentMic();
	$('agent-meter').style.width = '0';
}

async function agentMute() {
	if (!agent.callId) return;
	timeline.mutes.push(performance.now());
	try {
		const res = await fetch(`/api/v1/calls/${encodeURIComponent(agent.callId)}/mute`, { method: 'POST' });
		const body = await res.json();
		logTo(agent.log, res.ok ? 'sys' : 'err', res.ok ? `차단: ${body.mutedFrames}프레임 (${body.mutedFrames * 20}ms)` : `차단 실패 ${res.status}`);
	} catch (e) {
		logTo(agent.log, 'err', `차단 실패: ${e.message}`);
	}
}

if (HAS_AGENT) {
$('agent-id').value = randomId('agent');
$('agent-connect').onclick = agentConnect;
$('agent-disconnect').onclick = () => agent.conn && agent.conn.ws.close();
$('agent-ready').onclick = () => agent.conn.send({ type: 'status', state: 'READY' });
$('agent-away').onclick = () => agent.conn.send({ type: 'status', state: 'AWAY' });
$('agent-accept').onclick = () => agent.conn.send({ type: 'accept', callId: agent.callId });
$('agent-reject').onclick = () => agent.conn.send({ type: 'reject', callId: agent.callId });
$('agent-hangup').onclick = () => agent.conn.send({ type: 'hangup', callId: agent.callId });
$('agent-mute').onclick = agentMute;
$('agent-mic').onchange = (e) => {
	if (agent.state !== 'IN_CALL') return;
	if (e.target.checked) startAgentMic();
	else stopAgentMic();
};
document.addEventListener('keydown', (e) => {
	if ((e.key === 'm' || e.key === 'M') && !(e.target instanceof HTMLInputElement) && agent.state === 'IN_CALL') {
		agentMute();
	}
});
}

// ---------- 민원인 ----------

const citizen = {
	conn: null,
	callId: null,
	stopSource: null,
	sourceGeneration: 0,
	stopTimer: null,
	player: new Player(),
	log: $('citizen-log'),
	fileSamples: null,
};

function citizenUi(text, kind) {
	const calling = !!citizen.conn;
	$('citizen-call').disabled = calling;
	$('citizen-hangup').disabled = !calling;
	$('citizen-call-id').disabled = calling;
	$('citizen-new-id').disabled = calling;
	$('citizen-source').disabled = calling;
	if (text) setBadge($('citizen-state'), text, kind);
}

async function citizenCall() {
	const callId = $('citizen-call-id').value.trim();
	if (!/^[A-Za-z0-9_-]{1,64}$/.test(callId)) {
		logTo(citizen.log, 'err', '통화 ID는 영문·숫자·-·_ 1~64자');
		return;
	}
	const source = $('citizen-source').value;
	if (source === 'file' && !citizen.fileSamples) {
		logTo(citizen.log, 'err', '오디오 파일을 먼저 고르세요');
		$('citizen-file').click();
		return;
	}
	context();
	citizen.callId = callId;
	citizen.conn = openSocket(citizen.log, citizenMessage, citizenAudio, citizenClosed);
	citizenUi('연결 중', '');
	try {
		await citizen.conn.opened;
		citizen.conn.send({ type: 'join', role: 'CITIZEN', callId, audio: AUDIO_FORMAT });
	} catch {
		logTo(citizen.log, 'err', '접속 실패');
	}
}

function citizenMessage(msg) {
	switch (msg.type) {
		case 'queued':
			citizenUi(`대기 ${msg.position}번째`, 'warn');
			break;
		case 'agent-ringing':
			citizenUi('상담원 연결 중', 'warn');
			break;
		case 'connected':
			citizenUi('통화 중', 'active');
			citizen.player.reset();
			citizen.stopTimer = startCallTimer('citizen-timer');
			startCitizenSource();
			break;
		case 'call-ended':
			citizenUi('통화 종료', '');
			stopCitizenSource();
			stopCitizenTimer();
			break;
	}
}

function citizenAudio(frame) {
	citizen.player.play(frame);
}

function citizenClosed() {
	stopCitizenSource();
	stopCitizenTimer();
	citizen.conn = null;
	citizenUi();
	if ($('citizen-state').textContent !== '통화 종료') {
		setBadge($('citizen-state'), '전화 안 함', '');
	}
	$('citizen-call-id').value = randomId('call');
}

function sendCitizenFrame(frame) {
	if (!citizen.conn) return;
	citizen.conn.sendAudio(frame);
	const v = level(frame);
	$('citizen-meter').style.width = `${v * 100}%`;
	timeline.add(timeline.sent, v);
}

async function startCitizenSource() {
	stopCitizenSource();
	const generation = ++citizen.sourceGeneration;
	const source = $('citizen-source').value;
	try {
		if (source === 'mic') {
			const stop = await micSource(sendCitizenFrame);
			if (generation !== citizen.sourceGeneration) {
				stop();
				return;
			}
			citizen.stopSource = stop;
		} else if (source === 'tone') {
			citizen.stopSource = pcmSource(toneSamples(), sendCitizenFrame, true);
		} else {
			citizen.stopSource = pcmSource(citizen.fileSamples, sendCitizenFrame, true);
		}
		logTo(citizen.log, 'sys', `음성 보내기 시작 (${$('citizen-source').selectedOptions[0].textContent})`);
	} catch (e) {
		logTo(citizen.log, 'err', `음성 입력 사용 불가: ${e.message}`);
	}
}

function stopCitizenTimer() {
	if (citizen.stopTimer) {
		citizen.stopTimer();
		citizen.stopTimer = null;
	}
}

function stopCitizenSource() {
	citizen.sourceGeneration++;
	if (citizen.stopSource) {
		citizen.stopSource();
		citizen.stopSource = null;
	}
	$('citizen-meter').style.width = '0';
}

if (HAS_CITIZEN) {
$('citizen-call-id').value = randomId('call');
$('citizen-new-id').onclick = () => ($('citizen-call-id').value = randomId('call'));
$('citizen-call').onclick = citizenCall;
$('citizen-hangup').onclick = () => {
	if (!citizen.conn) return;
	citizen.conn.send({ type: 'hangup', callId: citizen.callId });
};
$('citizen-source').onchange = (e) => {
	if (e.target.value === 'file') $('citizen-file').click();
};
$('citizen-file').onchange = async (e) => {
	const file = e.target.files[0];
	if (!file) return;
	try {
		citizen.fileSamples = await fileSamples(file);
		logTo(citizen.log, 'sys', `파일 준비: ${file.name} (${(citizen.fileSamples.length / SAMPLE_RATE).toFixed(1)}초, 반복 재생)`);
	} catch (e2) {
		citizen.fileSamples = null;
		logTo(citizen.log, 'err', `파일을 읽지 못함: ${e2.message}`);
	}
};
}

// ---------- 게이트웨이 상태 ----------

const AGENT_STATES = { READY: '대기', AWAY: '자리 비움', RINGING: '벨 울림', IN_CALL: '통화 중' };
const CALL_STATES = { QUEUED: '대기열', RINGING: '벨 울림', CONNECTED: '통화 중' };

function cell(text, className) {
	const td = document.createElement('td');
	td.textContent = text;
	if (className) td.className = className;
	return td;
}

function fillTable(tbody, rows, columns, emptyText) {
	tbody.replaceChildren();
	if (rows.length === 0) {
		const tr = document.createElement('tr');
		const td = cell(emptyText, 'empty');
		td.colSpan = columns;
		tr.appendChild(td);
		tbody.appendChild(tr);
		return;
	}
	for (const values of rows) {
		const tr = document.createElement('tr');
		values.forEach((v) => tr.appendChild(cell(v)));
		tbody.appendChild(tr);
	}
}

function bytes(n) {
	return n < 1024 ? `${n}B` : n < 1048576 ? `${(n / 1024).toFixed(1)}KB` : `${(n / 1048576).toFixed(2)}MB`;
}

async function refreshStatus() {
	try {
		const res = await fetch('/api/v1/status');
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		const s = await res.json();
		$('server-dot').className = 'dot ok';
		$('server-text').textContent = '게이트웨이 연결됨';
		if (!state.delayMs) state.delayMs = s.delayMs;
		setText('delay-ms', `${s.delayMs / 1000}초`);
		const waiting = s.queue.length;
		setText('agent-queue-count', waiting ? `기다리는 민원인 ${waiting}명` : '기다리는 민원인 없음');
		if (!HAS_STATUS) return;
		setBadge($('status-config'), `지연 ${s.delayMs}ms · STT ${s.sttFormat}`, '');
		fillTable($('status-agents'),
			s.agents.map((a) => [a.agentId, AGENT_STATES[a.state] || a.state, a.callId || '-']), 3, '접속한 상담원 없음');
		$('status-queue').textContent = s.queue.length ? s.queue.map((id, i) => `${i + 1}. ${id}`).join('  ') : '비어 있음';
		fillTable($('status-calls'),
			s.calls.map((c) => [
				c.callId,
				CALL_STATES[c.state] || c.state,
				c.agentId || '-',
				c.state === 'CONNECTED' ? `${c.sttChunks}조각 · ${bytes(c.sttBytes)}` : '-',
				c.state === 'CONNECTED' ? `${c.mutedFrames}프레임` : '-',
			]), 5, '통화 없음');
		await refreshRecordings(s.sttRecording);
	} catch {
		$('server-dot').className = 'dot bad';
		$('server-text').textContent = '게이트웨이 응답 없음';
	}
}

let recordingsKey = '';

async function refreshRecordings(enabled) {
	const box = $('status-dumps');
	if (!enabled) {
		if (recordingsKey !== 'off') {
			box.replaceChildren(Object.assign(document.createElement('p'), {
				className: 'hint',
				textContent: 'voicegateway.stt.dump-dir을 설정하면 STT가 받은 음성을 여기서 들을 수 있다.',
			}));
			recordingsKey = 'off';
		}
		return;
	}
	const res = await fetch('/api/v1/stt/recordings');
	if (!res.ok) return; // 기록 목록 오류는 다음 조회에서 다시 시도한다
	const list = await res.json();
	// 재생기는 이름이 바뀔 때만 다시 만든다 (진행 중인 통화의 크기가 늘 때마다 만들면 듣던 재생이 끊김). 설명 줄은 매번 갱신
	const key = list.map((r) => r.name).join('|');
	const describe = (r) => `${r.callId} · ${(r.durationMs / 1000).toFixed(1)}초 · ${clock(new Date(r.startedAtEpochMs))}`;
	if (key === recordingsKey) {
		box.querySelectorAll('[data-name]').forEach((title) => {
			const r = list.find((x) => x.name === title.dataset.name);
			if (r) title.textContent = describe(r);
		});
		return;
	}
	recordingsKey = key;
	box.replaceChildren();
	if (list.length === 0) {
		box.appendChild(Object.assign(document.createElement('p'), { className: 'hint', textContent: '아직 기록 없음' }));
		return;
	}
	for (const r of list.slice(0, 5)) {
		const title = document.createElement('div');
		title.className = 'mono';
		title.dataset.name = r.name;
		title.textContent = describe(r);
		const audio = document.createElement('audio');
		audio.controls = true;
		audio.preload = 'none';
		audio.src = `/api/v1/stt/recordings/${encodeURIComponent(r.name)}`;
		box.append(title, audio);
	}
}

if (HAS_AGENT) agentUi();
if (HAS_CITIZEN) citizenUi();
// 앞 조회가 끝난 뒤 1초 뒤에 다음 조회 (느린 응답이 겹쳐 순서가 뒤바뀌지 않게)
(async function pollStatus() {
	await refreshStatus();
	setTimeout(pollStatus, 1000);
})();
requestAnimationFrame(drawTimeline);
