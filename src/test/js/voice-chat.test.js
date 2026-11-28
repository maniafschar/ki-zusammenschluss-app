const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
	constructor() {
		this.listeners = {};
		this.dataset = {};
		this.attributes = {};
		this.classList = { add() {}, remove() {}, contains() { return false; } };
		this.children = [];
	}
	addEventListener(type, listener) { this.listeners[type] = listener; }
	append(...children) { this.children.push(...children); }
	remove() {}
	replaceChildren(...children) { this.children = children; }
	setAttribute(name, value) { this.attributes[name] = value; }
	getAttribute(name) { return this.attributes[name] ?? null; }
	get textContent() {
		return this._textContent ?? this.children.map((child) => child.textContent || '').join('');
	}
	set textContent(value) { this._textContent = String(value); }
	querySelector(selector) {
		const matches = (node) => {
			if (selector === '.chat-choices' && !node.className?.split(' ').includes('chat-choices')) return false;
			if (selector === '.chat-assistant' && !node.className?.split(' ').includes('chat-assistant')) return false;
			if (selector.includes('[data-chat-choice]') && !node.dataset?.chatChoice) return false;
			if (selector.includes('[data-chat-submit-multiple]') && !Object.hasOwn(node.dataset || {}, 'chatSubmitMultiple')) return false;
			if (selector.includes('[aria-pressed="true"]') && node.getAttribute?.('aria-pressed') !== 'true') return false;
			return true;
		};
		const find = (node) => {
			for (const child of node.children || []) {
				if (matches(child)) return child;
				const match = find(child);
				if (match) return match;
			}
			return null;
		};
		return find(this);
	}
	scrollTo() {}
	scrollIntoView(options) { this.scrollOptions = options; }
	getContext() { return null; }
}

function setup(fetch) {
	const elements = new Map();
	const speechLabel = new Element();
	const speechToggle = new Element();
	speechToggle.querySelector = (selector) => selector === '.speech-toggle-label' ? speechLabel : null;
	elements.set('speech-toggle', speechToggle);
	const questionForm = new Element();
	questionForm.elements = { question: { value: '' } };
	elements.set('voice-question-form', questionForm);
	const bookingForm = new Element();
	bookingForm.elements = {};
	elements.set('booking-form', bookingForm);
	const document = {
		cookie: '',
		getElementById(id) {
			if (!elements.has(id)) elements.set(id, new Element());
			return elements.get(id);
		},
		createElement: () => new Element(),
		createTextNode: (text) => ({ textContent: text }),
		addEventListener() {}
	};
	const players = [];
	class Audio extends Element {
		constructor() { super(); this.paused = false; players.push(this); }
		play() { this.played = true; return Promise.resolve(); }
		pause() { this.paused = true; }
	}
	const window = {
		listeners: {},
		MediaRecorder: class {},
		matchMedia: () => ({ matches: true, addEventListener() {} }),
		addEventListener(type, listener) { this.listeners[type] = listener; }
	};
	const navigator = { mediaDevices: { getUserMedia: () => new Promise(() => {}) } };
	let source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.js'), 'utf8');
	source = source.slice(source.indexOf('(function voiceChat()'));
	const importStart = source.indexOf("\timport('https://");
	const importEnd = source.indexOf('\n\tfunction xsrfCookie', importStart);
	source = source.slice(0, importStart) + source.slice(importEnd);
	source = source.replace(/\n\}\)\(\);\s*$/, '\n globalThis.chat = { ask, stopTurn, submitChatAnswer, showPreviousQuestion, steps, history, get currentStepIndex() { return currentStepIndex; }, set currentStepIndex(value) { currentStepIndex = value; } };\n})();');
	const context = {
		window, document, navigator, fetch, Audio, AbortController, TextDecoder, Uint8Array,
		URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
		IntersectionObserver: class { observe() {} },
		requestAnimationFrame: () => 1, cancelAnimationFrame() {},
		console, setTimeout, clearTimeout
	};
	vm.runInNewContext(source, context);
	return { chat: context.chat, elements, players, window };
}

const ok = { ok: true, status: 200, headers: new Headers() };
const event = (type, payload) => new TextEncoder().encode(`event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`);
const tick = () => new Promise((resolve) => setImmediate(resolve));
async function until(predicate) {
	for (let i = 0; i < 100; i++) {
		if (predicate()) return;
		await tick();
	}
	assert.fail('Timed out waiting for expected activity');
}

test('the dialog starts only on request and can be retried after a welcome error', async () => {
	let welcomeRequests = 0;
	const { elements } = setup(async (url) => {
		if (url.includes('/welcome')) {
			welcomeRequests++;
			return welcomeRequests === 1
				? { ok: false, status: 503 }
				: { ok: true, json: async () => ({ gespraechId: 'test', text: '' }) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	const start = elements.get('welcome-start');
	assert.equal(welcomeRequests, 0);
	await start.listeners.click();
	assert.equal(start.disabled, false);
	assert.match(elements.get('voice-text').textContent, /Bitte versuche es erneut/);
	await start.listeners.click();
	assert.equal(welcomeRequests, 2);
	assert.equal(start.hidden, true);
});

test('a direct booking confirmation does not start the dialog', async () => {
	const { elements, window } = setup(async () => { throw new Error('Unexpected request'); });
	await window.listeners['termin-bestaetigt']({ detail: { start: '2026-11-11T10:00:00Z' } });
	assert.notEqual(elements.get('welcome-start').hidden, true);
	assert.equal(elements.get('kontakt').scrollOptions.behavior, 'auto');
});

test('a resumed confirmed dialog offers text input without microphone support', async () => {
	const { elements } = setup(async (url) => {
		if (url.endsWith('/welcome')) return {
			...ok, json: async () => ({ gespraechId: 'test', text: 'Wie kann ich helfen?', bestaetigt: true })
		};
		throw new Error(`Unexpected request: ${url}`);
	});
	await elements.get('welcome-start').listeners.click();
	assert.equal(elements.get('voice-question-form').hidden, false);
});

test('FAQ animations can reverse direction and respect reduced motion', () => {
	const summary = new Element();
	const animations = [];
	const answer = {
		scrollHeight: 100,
		getBoundingClientRect: () => ({ height: 40 }),
		animate(frames) {
			const animation = { frames, cancel() { this.cancelled = true; } };
			animations.push(animation);
			return animation;
		}
	};
	const details = {
		open: false,
		querySelector: (selector) => selector === 'summary' ? summary : answer
	};
	const motion = { matches: false };
	const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.js'), 'utf8');
	vm.runInNewContext(source.slice(source.indexOf('(function animateFaq()'), source.indexOf('(function voiceChat()')), {
		window: { matchMedia: () => motion },
		document: { querySelectorAll: () => [details] }
	});
	const click = () => summary.listeners.click({ preventDefault() {} });
	click();
	assert.equal(details.open, true);
	assert.equal(animations[0].frames[0].height, '0px');
	assert.equal(animations[0].frames[1].height, '100px');
	click();
	assert.equal(animations[0].cancelled, true);
	assert.equal(animations[1].frames[1].height, '0px');
	click();
	assert.equal(animations[1].cancelled, true);
	assert.equal(animations[2].frames[1].height, '100px');
	animations[2].onfinish();
	assert.equal(details.open, true);
	motion.matches = true;
	click();
	assert.equal(details.open, false);
	assert.equal(animations.length, 3);
});

test('enabling speech reads the currently displayed question and can be stopped', async () => {
	const inputs = [];
	const { elements, players } = setup(async (url, options) => {
		if (url.endsWith('/welcome')) return { ...ok, json: async () => ({ gespraechId: 'test', text: 'Was ist Dein Ziel?' }) };
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/speech')) {
			inputs.push(JSON.parse(options.body).input);
			return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	await elements.get('welcome-start').listeners.click();
	elements.get('speech-toggle').listeners.click();
	await until(() => players[0]?.played);
	assert.deepEqual(inputs, ['Was ist Dein Ziel?']);
	elements.get('speech-toggle').listeners.click();
	assert.equal(players[0].paused, true);
});

test('enabling speech during streaming reads the existing text and the remainder', async () => {
	let stream;
	const inputs = [];
	const { chat, elements, players } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/speech')) {
			inputs.push(JSON.parse(options.body).input);
			return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		}
		if (url.endsWith('/chat/stream')) return { ...ok, body: new ReadableStream({ start(c) { stream = c; } }) };
		throw new Error(`Unexpected request: ${url}`);
	});
	const turn = chat.ask('Antwort', ['Antwort'], true);
	await until(() => stream);
	const first = 'Dies ist der erste Teil der aktuellen Frage. ';
	const second = 'Dies ist der zweite Teil der aktuellen Frage.';
	stream.enqueue(event('delta', { text: first }));
	await tick();
	elements.get('speech-toggle').listeners.click();
	stream.enqueue(event('delta', { text: second }));
	stream.enqueue(event('done', { text: first + second }));
	await until(() => players[0]?.played);
	players[0].listeners.ended();
	await until(() => players[1]?.played);
	players[1].listeners.ended();
	await turn;
	assert.equal(inputs.join(' '), (first + second).trim());
});

test('speech can be switched off and back on while an answer is streaming', async () => {
	let stream;
	const inputs = [];
	const { chat, elements, players } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/speech')) {
			inputs.push(JSON.parse(options.body).input);
			return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		}
		if (url.endsWith('/chat/stream')) return { ...ok, body: new ReadableStream({ start(c) { stream = c; } }) };
		throw new Error(`Unexpected request: ${url}`);
	});
	elements.get('speech-toggle').listeners.click();
	const turn = chat.ask('Antwort', ['Antwort'], true);
	await until(() => stream);
	const reply = 'Welche Ziele hast Du genau? ';
	stream.enqueue(event('delta', { text: reply }));
	await until(() => players[0]?.played);
	elements.get('speech-toggle').listeners.click();
	elements.get('speech-toggle').listeners.click();
	stream.enqueue(event('done', { text: reply }));
	await until(() => players[1]?.played);
	assert.equal(players[0].paused, true);
	assert.equal(inputs[0], inputs[1]);
	players[1].listeners.ended();
	await turn;
});

test('the follow-up chat accepts a typed question and keeps the input available', async () => {
	let stream;
	const requests = [];
	const { chat, elements } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/sprueche')) return { ...ok, json: async () => [] };
		if (url.endsWith('/chat/stream')) {
			requests.push(JSON.parse(options.body));
			if (requests.length > 1) return new Promise(() => {});
			return { ...ok, body: new ReadableStream({ start(c) { stream = c; } }) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	const turn = chat.ask('Antwort', ['Antwort'], true);
	await until(() => stream);
	stream.enqueue(event('done', { text: 'Zusammenfassung', zusammenfassung: 'Ergebnis' }));
	await turn;
	const find = (node) => {
		if (node.textContent === 'Mit mir weiterchatten' && node.listeners?.click) return node;
		for (const child of node.children || []) {
			const match = find(child);
			if (match) return match;
		}
	};
	find(elements.get('voice-text')).listeners.click();
	const form = elements.get('voice-question-form');
	assert.equal(form.hidden, false);
	form.elements.question.value = '  Wie geht es weiter?  ';
	form.listeners.submit({ preventDefault() {} });
	await until(() => requests.length === 2);
	assert.equal(requests[1].messages.at(-1).content, 'Wie geht es weiter?');
	assert.equal(form.elements.question.value, '');
	assert.equal(form.hidden, false);
});

test('a completed answer continues playing its prefetched audio normally', async () => {
	let stream;
	const { chat, elements, players } = setup(async (url) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/speech')) return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		if (url.endsWith('/chat/stream')) {
			return { ...ok, body: new ReadableStream({ start(controller) { stream = controller; } }) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	elements.get('speech-toggle').listeners.click();
	const turn = chat.ask('Frage', ['Antwort'], true);
	await until(() => stream);
	const reply = 'Dies ist ein ausreichend langer erster Satz für die Sprachausgabe. '
		+ 'Dies ist ein weiterer ausreichend langer Satz für den nächsten Audioabschnitt.';
	stream.enqueue(event('delta', { text: reply }));
	stream.enqueue(event('done', { text: reply }));
	await until(() => players.length === 2 && players[0].played);
	players[0].listeners.ended();
	await until(() => players[1].played);
	players[1].listeners.ended();
	await turn;
});

test('switching questions stops playing and discards prefetched audio', async () => {
	let stream;
	let speechRequests = 0;
	let chatRequests = 0;
	const { chat, elements, players } = setup(async (url) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/sprueche')) return { ...ok, json: async () => [] };
		if (url.endsWith('/speech')) {
			speechRequests++;
			return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		}
		if (url.endsWith('/chat/stream')) {
			chatRequests++;
			if (chatRequests > 1) return new Promise(() => {});
			return { ...ok, body: new ReadableStream({ start(controller) { stream = controller; } }) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	elements.get('speech-toggle').listeners.click();
	const first = chat.ask('Erste Frage', ['Antwort'], true);
	await until(() => stream);
	const reply = 'Dies ist ein ausreichend langer erster Satz für die Sprachausgabe. '
		+ 'Dies ist ein weiterer ausreichend langer Satz für den nächsten Audioabschnitt. '
		+ 'Auch dieser dritte Satz darf nach dem Fragenwechsel nicht mehr vorgelesen werden.';
	stream.enqueue(event('delta', { text: reply }));
	stream.enqueue(event('done', { text: reply }));
	await until(() => players.length >= 2 && players[0].played);
	const prefetched = speechRequests;
	assert.ok(prefetched >= 2);
	chat.submitChatAnswer('Nächste Antwort');
	await until(() => chatRequests === 2);
	await first;
	assert.equal(players[0].paused, true);
	assert.equal(players[1].played, undefined);
	assert.equal(speechRequests, prefetched);
});

test('starting the microphone aborts the answer stream and prevents delayed speech requests', async () => {
	let stream;
	let chatSignal;
	let releaseSpeechCsrf;
	let csrfCalls = 0;
	let speechRequests = 0;
	const speechCsrf = new Promise((resolve) => { releaseSpeechCsrf = () => resolve(ok); });
	const { chat, elements, players } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) {
			csrfCalls++;
			if (csrfCalls > 1) return speechCsrf;
			return ok;
		}
		if (url.endsWith('/speech')) {
			speechRequests++;
			return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		}
		if (url.endsWith('/chat/stream')) {
			chatSignal = options.signal;
			return { ...ok, body: new ReadableStream({
				start(controller) {
					stream = controller;
					chatSignal.addEventListener('abort', () => controller.error(chatSignal.reason));
				}
			}) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	elements.get('speech-toggle').listeners.click();
	const first = chat.ask('Erste Frage', ['Antwort'], true);
	await until(() => stream);
	stream.enqueue(event('delta', { text: 'Dies ist ein ausreichend langer Satz, der gerade vorgelesen werden soll. ' }));
	await until(() => csrfCalls > 1);
	elements.get('voice-mic').listeners.click();
	assert.equal(chatSignal.aborted, true);
	releaseSpeechCsrf();
	await first;
	await tick();
	assert.equal(speechRequests, 0);
	assert.equal(players.length, 0);
});

test('a new question excludes an interrupted turn even while its reader is still pending', async () => {
	const requests = [];
	let firstStream;
	const { chat } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/chat/stream')) {
			requests.push(JSON.parse(options.body));
			if (requests.length > 1) return new Promise(() => {});
			return { ...ok, body: new ReadableStream({ start(controller) { firstStream = controller; } }) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	const first = chat.ask('Unterbrochene Frage', ['Antwort'], true);
	await until(() => firstStream);
	chat.stopTurn();
	chat.ask('Neue Frage', ['Antwort'], true);
	await until(() => requests.length === 2);
	assert.deepEqual(requests[1].messages, [{ role: 'user', content: 'Neue Frage' }]);
	firstStream.close();
	await first;
});

test('answering again after going back preserves prior messages without replaying speech', async () => {
	let requests = 0;
	const bodies = [];
	const { chat, elements } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/chat/stream')) {
			requests++;
			bodies.push(JSON.parse(options.body));
			return new Promise(() => {});
		}
		throw new Error(`Unexpected request: ${url}`);
	});
	chat.steps.push({
		question: 'Beta',
		reply: 'Mehrfachauswahl\n- Alpha\n- Beta',
		choices: true,
		selectedOptions: ['Beta'],
		answerValue: 'Beta',
		historyLength: 3
	}, {
		question: 'Gamma',
		reply: 'Nächste Frage:\n- Ja\n- Nein',
		choices: true,
		selectedOptions: [],
		answerValue: '',
		historyLength: 5
	});
	chat.currentStepIndex = 1;
	chat.history.push(
		{ role: 'assistant', content: 'Begrüßung' },
		{ role: 'user', content: 'Beta' },
		{ role: 'assistant', content: 'Mehrfachauswahl\n- Alpha\n- Beta' },
		{ role: 'user', content: 'Gamma' },
		{ role: 'assistant', content: 'Nächste Frage' }
	);

	chat.showPreviousQuestion();

	assert.equal(chat.currentStepIndex, 0);
	assert.equal(chat.history.length, 3);
	const find = (node, predicate) => {
		if (predicate(node)) return node;
		for (const child of node.children || []) {
			const match = find(child, predicate);
			if (match) return match;
		}
		return null;
	};
	const findChoice = (node) => {
		if (node.dataset?.chatChoice === 'Beta') return node;
		for (const child of node.children || []) {
			const match = findChoice(child);
			if (match) return match;
		}
		return null;
	};
	assert.equal(findChoice(elements.get('voice-text')).getAttribute('aria-pressed'), 'true');
	assert.equal(find(elements.get('voice-text'), (node) => Object.hasOwn(node.dataset || {}, 'chatSubmitMultiple')).disabled, false);
	chat.submitChatAnswer('Alpha', ['Alpha']);
	await until(() => requests === 1);
	assert.deepEqual(bodies[0].messages, [
		{ role: 'assistant', content: 'Begrüßung' },
		{ role: 'user', content: 'Beta' },
		{ role: 'assistant', content: 'Mehrfachauswahl\n- Alpha\n- Beta' },
		{ role: 'user', content: 'Alpha' }
	]);
	assert.equal(requests, 1);
});
