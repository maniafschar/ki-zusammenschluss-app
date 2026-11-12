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
	setAttribute(name, value) { this[name] = value; }
	addEventListener(type, listener) { this.listeners[type] = listener; }
	appendChild(child) { this.children.push(child); }
	scrollIntoView(options) { this.scrollOptions = options; }
}

async function setup({ mobile, reducedMotion, slots = [{ start: '2026-11-11T10:00:00Z', frei: true }], holidays = {} }) {
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
	const fetch = async (url) => ({
		ok: true,
		json: async () => url.endsWith('/feiertage') ? holidays : slots
	});
	const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.js'), 'utf8');
	const booking = source.slice(source.indexOf('function startBooking()'), source.indexOf('\nasync function loadSection'));
	vm.runInNewContext(booking + '\nstartBooking();', { document, window, fetch, URLSearchParams, console, confirmToken: null });
	await new Promise((resolve) => setImmediate(resolve));
	return elements;
}

test('holiday remains clickable but cannot be booked, even with a free slot', async () => {
	const elements = await setup({
		mobile: false, reducedMotion: false,
		slots: [
			{ start: '2026-11-11T10:00:00Z', frei: true },
			{ start: '2026-11-18T10:00:00Z', frei: true }
		],
		holidays: { '2026-11-18': 'Buß- und Bettag', '2026-11-01': 'Allerheiligen' }
	});
	elements.get('cal-days').children.find((child) => child.textContent === 11).listeners.click();
	elements.get('slots').children[0].listeners.click();
	assert.equal(elements.get('booking-form').hidden, false);
	const holiday = elements.get('cal-days').children.find((child) => child.textContent === 18);
	assert.equal(holiday.disabled, false);
	assert.match(holiday['aria-label'], /Buß- und Bettag/);
	holiday.listeners.click();
	assert.equal(elements.get('booking-form').hidden, true);
	assert.equal(elements.get('slots').children.length, 0);
	assert.match(elements.get('cal-status').textContent, /Buß- und Bettag/);
	const weekend = elements.get('cal-days').children.find((child) => child.textContent === 1);
	weekend.listeners.click();
	assert.match(elements.get('cal-status').textContent, /Allerheiligen/);
});

test('holiday API failure prevents calendar selection', async () => {
	const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.js'), 'utf8');
	const booking = source.slice(source.indexOf('function startBooking()'), source.indexOf('\nasync function loadSection'));
	const elements = new Map();
	const document = { getElementById(id) {
		if (!elements.has(id)) elements.set(id, new Element());
		return elements.get(id);
	} };
	const fetch = async (url) => url.endsWith('/feiertage')
		? { ok: false, status: 503 }
		: { ok: true, json: async () => [] };
	vm.runInNewContext(booking + '\nstartBooking();', {
		document, window: { location: { search: '' } }, fetch, URLSearchParams, console: { error() {} }, confirmToken: null
	});
	await new Promise((resolve) => setImmediate(resolve));
	assert.equal(elements.get('cal-status').textContent, 'Termine konnten nicht geladen werden.');
});

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
