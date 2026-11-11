const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
	constructor() {
		this.listeners = {};
		this.children = [];
		this.classList = { add() {}, remove() {} };
	}
	set innerHTML(value) { this.children = []; }
	addEventListener(type, listener) { this.listeners[type] = listener; }
	appendChild(child) { this.children.push(child); }
	scrollIntoView(options) { this.scrollOptions = options; }
}

async function setup({ mobile, reducedMotion }) {
	const elements = new Map();
	const document = {
		getElementById(id) {
			if (!elements.has(id)) elements.set(id, new Element());
			return elements.get(id);
		},
		createElement: () => new Element()
	};
	const window = {
		location: { search: '' },
		matchMedia(query) {
			if (query === '(max-width: 760px)') return { matches: mobile };
			if (query === '(prefers-reduced-motion: reduce)') return { matches: reducedMotion };
			throw new Error(`Unexpected media query: ${query}`);
		}
	};
	const fetch = async () => ({
		ok: true,
		json: async () => [{ start: '2026-11-11T10:00:00', frei: true }]
	});
	const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.js'), 'utf8');
	const booking = source.slice(source.indexOf('function startBooking()'), source.indexOf('\nasync function loadSection'));
	vm.runInNewContext(booking + '\nstartBooking();', { document, window, fetch, URLSearchParams, console });
	await new Promise((resolve) => setImmediate(resolve));
	return elements;
}

for (const [description, mobile, reducedMotion, behavior] of [
	['mobile view scrolls smoothly', true, false, 'smooth'],
	['reduced motion disables smooth scrolling', true, true, 'auto'],
	['desktop view does not scroll', false, false, null]
]) {
	test(description, async () => {
		const elements = await setup({ mobile, reducedMotion });
		const day = elements.get('cal-days').children.find((child) => child.textContent === 11);
		day.listeners.click();
		assert.equal(elements.get('slots').children.length, 1);
		const options = elements.get('slots-title').scrollOptions;
		assert.equal(options?.behavior ?? null, behavior);
		if (options) assert.equal(options.block, 'start');
		elements.get('slots').children[0].listeners.click();
		assert.equal(elements.get('slots-title').scrollOptions, options);
	});
}
