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
	getContext() { return null; }
}

function setup(fetch) {
	const elements = new Map();
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
		MediaRecorder: class {},
		matchMedia: () => ({ matches: true, addEventListener() {} }),
		addEventListener() {}
	};
	const navigator = { mediaDevices: { getUserMedia: () => new Promise(() => {}) } };
	let source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.js'), 'utf8');
	source = source.slice(source.indexOf('(function voiceChat()'));
	const importStart = source.indexOf("\timport('https://");
	const importEnd = source.indexOf('\n\tasync function csrfToken', importStart);
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
	return { chat: context.chat, elements, players };
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

test('a completed answer continues playing its prefetched audio normally', async () => {
	let stream;
	const { chat, players } = setup(async (url) => {
		if (url.endsWith('/csrf')) return ok;
		if (url.endsWith('/speech')) return { ...ok, body: null, blob: async () => new Blob(['audio']) };
		if (url.endsWith('/chat/stream')) {
			return { ...ok, body: new ReadableStream({ start(controller) { stream = controller; } }) };
		}
		throw new Error(`Unexpected request: ${url}`);
	});
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
	const { chat, players } = setup(async (url) => {
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
	const first = chat.ask('Erste Frage', ['Antwort'], true);
	await until(() => stream);
	const reply = 'Dies ist ein ausreichend langer erster Satz für die Sprachausgabe. '
		+ 'Dies ist ein weiterer ausreichend langer Satz für den nächsten Audioabschnitt. '
		+ 'Auch dieser dritte Satz darf nach dem Fragenwechsel nicht mehr vorgelesen werden.';
	stream.enqueue(event('delta', { text: reply }));
	stream.enqueue(event('done', { text: reply }));
	await until(() => players.length >= 2 && players[0].played);
	assert.equal(speechRequests, 2);
	chat.submitChatAnswer('Nächste Antwort');
	await until(() => chatRequests === 2);
	await first;
	assert.equal(players[0].paused, true);
	assert.equal(players[1].played, undefined);
	assert.equal(speechRequests, 2);
});

test('starting the microphone aborts the answer stream and prevents delayed speech requests', async () => {
	let stream;
	let chatSignal;
	let releaseSpeechCsrf;
	let csrfCalls = 0;
	let speechRequests = 0;
	const { chat, elements, players } = setup(async (url, options) => {
		if (url.endsWith('/csrf')) {
			csrfCalls++;
			if (csrfCalls === 2) return new Promise((resolve) => { releaseSpeechCsrf = () => resolve(ok); });
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
	const first = chat.ask('Erste Frage', ['Antwort'], true);
	await until(() => stream);
	stream.enqueue(event('delta', { text: 'Dies ist ein ausreichend langer Satz, der gerade vorgelesen werden soll. ' }));
	await until(() => releaseSpeechCsrf);
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
