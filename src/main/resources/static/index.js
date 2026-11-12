const confirmParams = new URLSearchParams(window.location.search);
if (confirmParams.get('type') === 'termine/bestaetigen' && confirmParams.get('token')) {
	window.location.replace('/ai/api/termine/bestaetigen?token=' + encodeURIComponent(confirmParams.get('token')));
}

(function spiderToggle() {
	const spider = document.getElementById('spider');
	const core = document.getElementById('spider-core');
	const action = core.querySelector('.spider-action');
	const panel = document.getElementById('spider-panel');
	const lines = panel.querySelector('.spider-lines');
	const connectors = lines.querySelectorAll('line');
	const benefits = panel.querySelectorAll('.spider-benefit');
	const activeTransitions = new Map();
	let frame = null;

	function updateConnectors() {
		frame = null;
		const bounds = lines.getBoundingClientRect();
		if (bounds.width && bounds.height) {
			const center = core.getBoundingClientRect();
			const x1 = (center.left + center.width / 2 - bounds.left) / bounds.width * 100;
			const y1 = (center.top + center.height / 2 - bounds.top) / bounds.height * 100;
			const targets = Array.from(benefits, (benefit) =>
				benefit.querySelector('.spider-benefit-toggle').getBoundingClientRect());
			targets.forEach((target, index) => {
				const line = connectors[index];
				line.setAttribute('x1', x1);
				line.setAttribute('y1', y1);
				line.setAttribute('x2', (target.left + target.width / 2 - bounds.left) / bounds.width * 100);
				line.setAttribute('y2', (target.top + target.height / 2 - bounds.top) / bounds.height * 100);
			});
		}
		if (activeTransitions.size) scheduleConnectors();
	}

	function scheduleConnectors() {
		if (frame === null) frame = requestAnimationFrame(updateConnectors);
	}

	const resizeObserver = new ResizeObserver(() => {
		if (frame !== null) cancelAnimationFrame(frame);
		updateConnectors();
	});
	[core, lines, ...benefits].forEach((element) => resizeObserver.observe(element));
	// ResizeObserver does not report the legs' transform animations.
	spider.addEventListener('transitionrun', (event) => {
		if (!event.target.matches('.spider-core, .spider-benefit, .spider-benefit-details')) return;
		if (!activeTransitions.has(event.target)) activeTransitions.set(event.target, new Set());
		activeTransitions.get(event.target).add(event.propertyName);
		scheduleConnectors();
	});
	function finishTransition(event) {
		const properties = activeTransitions.get(event.target);
		if (!properties) return;
		properties.delete(event.propertyName);
		if (!properties.size) activeTransitions.delete(event.target);
		scheduleConnectors();
	}
	spider.addEventListener('transitionend', finishTransition);
	spider.addEventListener('transitioncancel', finishTransition);
	scheduleConnectors();

	core.addEventListener('click', () => {
		const open = !spider.classList.contains('is-open');
		spider.classList.toggle('is-open', open);
		core.setAttribute('aria-expanded', String(open));
		panel.inert = !open;
		action.textContent = open ? 'Vorteile schließen −' : 'Vorteile entdecken +';
	});
	panel.querySelectorAll('.spider-benefit-toggle').forEach((toggle) => {
		toggle.addEventListener('click', () => {
			const open = toggle.getAttribute('aria-expanded') !== 'true';
			toggle.closest('.spider-benefit').classList.toggle('is-expanded', open);
			toggle.setAttribute('aria-expanded', String(open));
			document.getElementById(toggle.getAttribute('aria-controls')).inert = !open;
		});
	});
})();

(function navigation() {
	const nav = document.querySelector('nav');
	const brand = nav.querySelector('strong');
	const toggle = nav.querySelector('.nav-toggle');
	const links = document.getElementById('main-nav-links');
	const measure = links.cloneNode(true);
	measure.removeAttribute('id');
	measure.className = 'nav-measure';
	measure.setAttribute('aria-hidden', 'true');
	measure.setAttribute('inert', '');
	document.body.appendChild(measure);
	let collapsed = false;

	function setOpen(open) {
		if (collapsed && !open && links.contains(document.activeElement)) toggle.focus();
		links.classList.toggle('is-open', collapsed && open);
		links.inert = collapsed && !open;
		toggle.setAttribute('aria-expanded', String(open));
		toggle.setAttribute('aria-label', open ? 'Menü schließen' : 'Menü öffnen');
	}

	function updateLayout() {
		const style = getComputedStyle(nav);
		const available = nav.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
		const needsMenu = brand.getBoundingClientRect().width + measure.scrollWidth + parseFloat(style.columnGap) > available;
		if (needsMenu === collapsed) return;
		const hadFocus = nav.contains(document.activeElement);
		collapsed = needsMenu;
		nav.classList.toggle('is-collapsed', collapsed);
		toggle.hidden = !collapsed;
		setOpen(false);
		if (hadFocus) (collapsed ? toggle : links.querySelector('a')).focus();
	}

	toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') !== 'true'));
	links.addEventListener('click', (event) => {
		if (collapsed && event.target.closest('a')) setOpen(false);
	});
	document.addEventListener('click', (event) => {
		if (collapsed && !nav.contains(event.target)) setOpen(false);
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && collapsed && toggle.getAttribute('aria-expanded') === 'true') {
			setOpen(false);
			toggle.focus();
		}
	});
	new ResizeObserver(updateLayout).observe(nav);
	window.addEventListener('resize', updateLayout);
	document.fonts.ready.then(updateLayout);
	updateLayout();
})();

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
	'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[c]));

function startBooking() {
	const MONTHS = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August',
		'September', 'Oktober', 'November', 'Dezember'];
	const el = (id) => document.getElementById(id);
	const bookingForm = el('booking-form');
	const pad = (n) => String(n).padStart(2, '0');
	const dateKey = (y, m, d) => `${y}-${pad(m + 1)}-${pad(d)}`;

	let slotsByDay = {};
	let firstMonth = null;
	let lastMonth = null;
	let view = null;
	let selectedDay = null;
	let selectedSlot = null;

	const timeOf = (iso) => new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
	const monthIndex = (key) => Number(key.slice(0, 4)) * 12 + Number(key.slice(5, 7)) - 1;

	function setMessage(text, kind = '') {
		const box = el('booking-message');
		box.textContent = text;
		box.className = 'booking-hint ' + kind;
	}

	async function loadSlots() {
		try {
			const slots = await fetch('/ai-expert/api/termine').then((r) => {
				if (!r.ok) throw new Error('HTTP ' + r.status);
				return r.json();
			});
			slotsByDay = {};
			slots.forEach((s) => {
				const d = new Date(s.start);
				(slotsByDay[dateKey(d.getFullYear(), d.getMonth(), d.getDate())] ||= []).push(s);
			});
			const days = Object.keys(slotsByDay).sort();
			if (!days.length) {
				el('cal-status').textContent = 'Aktuell sind keine Termine verfügbar.';
				return;
			}
			firstMonth = monthIndex(days[0]);
			lastMonth = monthIndex(days[days.length - 1]);
			if (view === null || view < firstMonth || view > lastMonth) view = firstMonth;
			el('cal-status').textContent = '';
			renderCalendar();
			if (selectedDay) renderSlots();
		} catch (err) {
			el('cal-status').textContent = 'Termine konnten nicht geladen werden.';
			console.error(err);
		}
	}

	function renderCalendar() {
		const year = Math.floor(view / 12);
		const month = view % 12;
		el('cal-title').textContent = `${MONTHS[month]} ${year}`;
		el('cal-prev').disabled = view <= firstMonth;
		el('cal-next').disabled = view >= lastMonth;

		const grid = el('cal-days');
		grid.innerHTML = '';
		const offset = (new Date(year, month, 1).getDay() + 6) % 7;
		for (let i = 0; i < offset; i += 1) grid.appendChild(document.createElement('span'));
		const daysInMonth = new Date(year, month + 1, 0).getDate();
		for (let d = 1; d <= daysInMonth; d += 1) {
			const key = dateKey(year, month, d);
			const free = (slotsByDay[key] || []).some((s) => s.frei);
			const btn = document.createElement('button');
			btn.type = 'button';
			btn.textContent = d;
			btn.className = 'cal-day' + (free ? ' available' : '') + (key === selectedDay ? ' selected' : '');
			btn.disabled = !free;
			if (free) {
				btn.addEventListener('click', () => {
					el('slots').classList.add('slots');
					selectedDay = key;
					selectedSlot = null;
					bookingForm.hidden = true;
					renderCalendar();
					renderSlots();
					if (window.matchMedia('(max-width: 760px)').matches) {
						el('slots-title').scrollIntoView({
							behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
							block: 'start'
						});
					}
				});
			}
			grid.appendChild(btn);
		}
	}

	function renderSlots() {
		const box = el('slots');
		box.innerHTML = '';
		const day = new Date(selectedDay + 'T00:00:00');
		el('slots-title').textContent = day.toLocaleDateString('de-DE',
			{ weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
		(slotsByDay[selectedDay] || []).forEach((s) => {
			const btn = document.createElement('button');
			btn.type = 'button';
			btn.textContent = timeOf(s.start) + ' Uhr';
			btn.className = 'slot' + (s.start === selectedSlot ? ' selected' : '');
			btn.disabled = !s.frei;
			btn.addEventListener('click', () => {
				selectedSlot = s.start;
				el('booking-selected').textContent =
					`Dein Termin: ${day.toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' })}, ${timeOf(s.start)} Uhr (30 Minuten)`;
				bookingForm.hidden = false;
				setMessage('');
				renderSlots();
			});
			box.appendChild(btn);
		});
	}

	el('cal-prev').addEventListener('click', () => { view -= 1; renderCalendar(); });
	el('cal-next').addEventListener('click', () => { view += 1; renderCalendar(); });

	bookingForm.addEventListener('submit', async (e) => {
		e.preventDefault();
		if (!selectedSlot) return;
		const submit = bookingForm.querySelector('button[type="submit"]');
		submit.disabled = true;
		try {
			await fetch('/ai-expert/api/csrf', { credentials: 'same-origin' });
			const token = decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || '');
			const res = await fetch('/ai-expert/api/termine', {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
				body: JSON.stringify({
					start: selectedSlot,
					zeitzone: Intl.DateTimeFormat().resolvedOptions().timeZone,
					name: bookingForm.elements.name.value,
					email: bookingForm.elements.email.value,
					telefon: bookingForm.elements.telefon.value,
					nachricht: bookingForm.elements.nachricht.value,
					gespraechId: bookingForm.dataset.gespraechId || null
				})
			});
			if (res.status === 409) {
				setMessage('Dieser Termin wurde gerade vergeben. Bitte wähle einen anderen.', 'error');
				selectedSlot = null;
				await loadSlots();
				return;
			}
			if (!res.ok) throw new Error('HTTP ' + res.status);
			bookingForm.reset();
			bookingForm.hidden = true;
			selectedSlot = null;
			await loadSlots();
			el('slots-title').textContent = 'Fast geschafft!';
			setMessage('', '');
			bookingForm.hidden = true;
			el('slots').classList.remove('slots');
			el('slots').innerHTML = '<p class="booking-hint ok">Wir haben Dir eine E-Mail mit einem Bestätigungslink geschickt. '
				+ 'Bitte bestätige Deine Adresse innerhalb von 10 Minuten, sonst wird der Termin wieder freigegeben.</p>';
		} catch (err) {
			setMessage('Die Buchung hat nicht geklappt. Bitte versuche es erneut.', 'error');
			console.error(err);
		} finally {
			submit.disabled = false;
		}
	});

	loadSlots().then(() => {
		const result = new URLSearchParams(window.location.search).get('termin');
		const texts = {
			bestaetigt: ['Vielen Dank! Dein Termin ist bestätigt.', 'ok'],
			abgelaufen: ['Der Bestätigungslink ist abgelaufen. Bitte buche erneut.', 'error'],
			ungueltig: ['Der Bestätigungslink ist ungültig.', 'error']
		};
		if (texts[result]) {
			el('slots-title').textContent = texts[result][0];
			el('slots-title').className = 'booking-hint ' + texts[result][1];
		}
	});
}

async function loadSection(url, sectionId, listId, render) {
	const section = document.getElementById(sectionId);
	const menuLink = sectionId === 'produkte' ? document.querySelector('.nav-links a[href="#produkte"]') : null;
	try {
		const items = await fetch(url).then((r) => r.json());
		section.hidden = items.length === 0;
		if (menuLink) menuLink.hidden = items.length === 0;
		document.getElementById(listId).innerHTML = items.map(render).join('');
	} catch (err) {
		section.hidden = true;
		if (menuLink) menuLink.hidden = true;
		console.error(err);
	}
}

loadSection('/ai-expert/api/leistungen', 'leistungen', 'leistungen-liste', (l) =>
	`<div class="card"><h3>${esc(l.titel)}</h3><p>${esc(l.text)}</p></div>`);

loadSection('/ai-expert/api/produkte', 'produkte', 'produkte-liste', (p) =>
	`<div class="card"><h3>${esc(p.name)}</h3><p>${esc(p.beschreibung)}</p>` +
	`<p class="price">${p.preis ? esc(p.preis) : 'Preis auf Anfrage'}</p></div>`);

loadSection('/ai-expert/api/experten', 'experten', 'experten-liste', (e) =>
	`<article class="card expert-card"><img src="${esc(e.bild)}" ` +
	`alt="Profilbild von ${esc(e.name)}" loading="lazy"><h3>${esc(e.name)}</h3>` +
	`<p class="role">${esc(e.rolle)}</p><p>${esc(e.beschreibung)}</p></article>`);

startBooking();

(function voiceChat() {
	const mic = document.getElementById('voice-mic');
	const text = document.getElementById('voice-text');
	const start = document.getElementById('welcome-start');
	const privacy = document.getElementById('voice-privacy');
	const privacyPopup = document.getElementById('voice-privacy-popup');
	const canvas = document.getElementById('voice-canvas');
	const webglCanvas = document.getElementById('voice-webgl');
	const ctx = canvas.getContext('2d');
	const canRecord = Boolean(window.MediaRecorder && navigator.mediaDevices?.getUserMedia);
	if (!canRecord) {
		mic.disabled = true;
		mic.title = 'Audioaufnahme wird von diesem Browser nicht unterstützt';
	}
	const history = [];
	let gespraechId = null;
	let state = 'idle'; // idle | listening | thinking | speaking
	let recorder = null;
	let chatTurn = 0;
	let activeTurn = null;
	let sprueche = [];
	let spruecheLaden = null;
	const verwendeteSprueche = new Set();
	let activeSpeech = null;
	let choiceReady = false;
	let level = 0;
	let audioContext = null;
	let audioAnalyser = null;
	let audioSamples = null;
	let audioFrequencies = null;
	let audioLevel = 0;
	let audioPitch = 180;
	const audioBands = [0, 0, 0];
	let visual = null;
	let visible = true;
	let frame = null;
	let lastFrame = 0;
	const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
	const inlineMarkdown = /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\*[^*]+\*|_[^_]+_|\[[^\]]+\]\(https?:\/\/[^\s)]+\))/g;

	function ladeSprueche() {
		if (!spruecheLaden) {
			spruecheLaden = fetch('/ai-expert/api/ki/sprueche')
				.then((response) => {
					if (!response.ok) throw new Error('HTTP ' + response.status);
					return response.json();
				})
				.then((items) => { sprueche = items; })
				.catch((err) => {
					console.error('KI-Sprüche konnten nicht geladen werden:', err);
				});
		}
		return spruecheLaden;
	}

	function naechsterSpruch() {
		const verfuegbar = sprueche.filter((spruch) => !verwendeteSprueche.has(spruch.id));
		if (!verfuegbar.length) return '';
		const spruch = verfuegbar[Math.floor(Math.random() * verfuegbar.length)];
		verwendeteSprueche.add(spruch.id);
		return spruch.text;
	}

	function appendInlineMarkdown(parent, value) {
		let cursor = 0;
		for (const match of value.matchAll(inlineMarkdown)) {
			const token = match[0];
			const index = match.index;
			parent.append(document.createTextNode(value.slice(cursor, index)));
			let element;
			if (token.startsWith('**') || token.startsWith('__')) {
				element = document.createElement('strong');
				element.textContent = token.slice(2, -2);
			} else if (token.startsWith('`')) {
				element = document.createElement('code');
				element.textContent = token.slice(1, -1);
			} else if (token.startsWith('*') || token.startsWith('_')) {
				element = document.createElement('em');
				element.textContent = token.slice(1, -1);
			} else {
				const [, label, href] = token.match(/^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/);
				element = document.createElement('a');
				element.href = href;
				element.target = '_blank';
				element.rel = 'noopener noreferrer';
				element.textContent = label;
			}
			parent.append(element);
			cursor = index + token.length;
		}
		parent.append(document.createTextNode(value.slice(cursor)));
	}

	function plainMarkdown(markdown) {
		return String(markdown ?? '').replace(/^\s{0,3}#{1,3}\s+/gm, '')
			.replace(/^\s*(?:[-+*]|\d+[.)])\s+/gm, '')
			.replace(/\[([^\]]+)\]\(https?:\/\/[^\s)]+\)/g, '$1')
			.replace(/\*\*|__|\*|_|`/g, '').trim();
	}

	function renderMarkdown(parent, markdown, choices = false) {
		const source = String(markdown ?? '');
		const multiple = choices && /Mehrfachauswahl/i.test(source);
		const maxMatch = source.match(/max(?:imal)?\.?\s*(\d+)\s*Antworten?/i);
		const maxSelections = maxMatch ? Number(maxMatch[1]) : 0;
		let list = null;
		let listType = '';
		let choiceGroup = null;
		const closeList = () => {
			if (choiceGroup?.dataset.selectionMode === 'multiple'
				&& !choiceGroup.querySelector('[data-chat-submit-multiple]')) {
				const submit = document.createElement('button');
				submit.type = 'button';
				submit.className = 'chat-multiselect-submit';
				submit.dataset.chatSubmitMultiple = '';
				submit.textContent = 'Auswahl übernehmen';
				submit.disabled = true;
				choiceGroup.append(submit);
			}
			list = null;
			listType = '';
			choiceGroup = null;
		};
		for (const line of source.split(/\r?\n/)) {
			const freeText = choices && line.match(/^\s*(?:[-+*]\s+)?(?:\*\s*)?\[?Freitextfeld:\s*(.+?)(?:\])?\*?\s*$/i);
			if (freeText) {
				closeList();
				const form = document.createElement('form');
				form.className = 'chat-text-entry';
				form.dataset.chatTextForm = '';
				const input = document.createElement('input');
				input.type = 'text';
				input.name = 'answer';
				input.maxLength = 2000;
				input.required = true;
				input.placeholder = freeText[1].replace(/\*+$/g, '').trim();
				input.setAttribute('aria-label', 'Deine Antwort');
				const submit = document.createElement('button');
				submit.type = 'submit';
				submit.className = 'chat-text-submit';
				submit.setAttribute('aria-label', 'Antwort senden');
				submit.title = 'Antwort senden';
				submit.textContent = 'Senden';
				form.append(input, submit);
				parent.append(form);
				continue;
			}
			const item = line.match(/^\s*(?:([-+*])\s+|(\d+)[.)]\s+)(.+)$/);
			if (item) {
				const type = item[2] ? 'ol' : 'ul';
				if (!list || listType !== type) {
					closeList();
					listType = type;
					if (choices) {
						choiceGroup = document.createElement('div');
						choiceGroup.className = 'chat-choices';
						choiceGroup.dataset.selectionMode = multiple ? 'multiple' : 'single';
						if (multiple) choiceGroup.dataset.maxSelections = String(maxSelections || Number.MAX_SAFE_INTEGER);
						parent.append(choiceGroup);
						list = document.createElement(type);
						list.className = 'chat-choice-list';
						choiceGroup.append(list);
					} else {
						list = document.createElement(type);
						list.className = 'chat-markdown-list';
						parent.append(list);
					}
				}
				const label = item[3].trim();
				const entry = document.createElement('li');
				if (choices) {
					const button = document.createElement('button');
					button.type = 'button';
					button.className = 'voice-choice';
					appendInlineMarkdown(button, label);
					button.dataset.chatChoice = button.textContent.trim();
					if (multiple) button.setAttribute('aria-pressed', 'false');
					entry.append(button);
				} else {
					appendInlineMarkdown(entry, label);
				}
				list.append(entry);
				continue;
			}
			closeList();
			if (!line.trim()) continue;
			const heading = line.match(/^#{1,3}\s+(.+)$/);
			const element = heading ? document.createElement('h3') : document.createElement('p');
			appendInlineMarkdown(element, heading ? heading[1] : line.trim());
			parent.append(element);
		}
		closeList();
	}

	function renderConversation(question, reply, {
		choices = false, status = '', thinking = false, thinkingQuote = ''
	} = {}) {
		text.replaceChildren();
		const user = document.createElement('div');
		user.className = 'chat-turn chat-user';
		const userLabel = document.createElement('strong');
		userLabel.textContent = 'Du';
		const userText = document.createElement('p');
		userText.textContent = question;
		user.append(userLabel, userText);
		text.append(user);
		const assistant = document.createElement('div');
		assistant.className = 'chat-turn chat-assistant';
		const assistantLabel = document.createElement('strong');
		assistantLabel.textContent = 'KI';
		assistant.append(assistantLabel);
		if (reply) renderMarkdown(assistant, reply, choices);
		if (thinking) {
			const indicator = document.createElement('div');
			indicator.className = 'chat-thinking';
			indicator.setAttribute('role', 'status');
			indicator.setAttribute('aria-label', 'Die KI formuliert eine Antwort');
			const orb = document.createElement('span');
			orb.className = 'thinking-orb';
			orb.setAttribute('aria-hidden', 'true');
			const copy = document.createElement('span');
			copy.className = 'thinking-copy';
			const label = document.createElement('span');
			label.className = 'thinking-label';
			label.textContent = 'KI denkt nach';
			copy.append(label);
			if (thinkingQuote) {
				const quote = document.createElement('span');
				quote.className = 'thinking-quote';
				quote.textContent = `„${thinkingQuote}“`;
				copy.append(quote);
			}
			indicator.append(orb, copy);
			assistant.append(indicator);
		}
		if (status) {
			const detail = document.createElement('p');
			detail.className = 'chat-status';
			detail.textContent = status;
			assistant.append(detail);
		}
		if (reply || status || thinking) text.append(assistant);
		text.scrollTo({ top: 0, behavior: reducedMotion.matches ? 'auto' : 'smooth' });
	}

	function draw(t) {
		frame = null;
		if (!visible) return;
		const delta = lastFrame ? Math.min((t - lastFrame) / 1000, 0.1) : 1 / 60;
		lastFrame = t;
		if (audioAnalyser && audioSamples) {
			audioAnalyser.getByteTimeDomainData(audioSamples);
			let energy = 0;
			for (const sample of audioSamples) {
				const amplitude = (sample - 128) / 128;
				energy += amplitude * amplitude;
			}
			const measuredLevel = Math.min(1, Math.sqrt(energy / audioSamples.length) * 4);
			const rate = measuredLevel > audioLevel ? 25 : 10;
			audioLevel += (measuredLevel - audioLevel) * (1 - Math.exp(-delta * rate));
			if (audioFrequencies) {
				audioAnalyser.getByteFrequencyData(audioFrequencies);
				const binHz = audioContext.sampleRate / audioAnalyser.fftSize;
				[[85, 300], [300, 1200], [1200, 4000]].forEach(([low, high], band) => {
					const from = Math.max(1, Math.floor(low / binHz));
					const to = Math.min(audioFrequencies.length, Math.ceil(high / binHz));
					let sum = 0;
					for (let i = from; i < to; i++) sum += audioFrequencies[i];
					const target = audioLevel > 0.02 ? sum / Math.max(1, to - from) / 255 : 0;
					audioBands[band] += (target - audioBands[band]) * (1 - Math.exp(-delta * (target > audioBands[band] ? 20 : 8)));
				});
				const minBin = Math.max(1, Math.floor(85 / binHz));
				const maxBin = Math.min(audioFrequencies.length - 1, Math.ceil(420 / binHz));
				let peakBin = minBin;
				for (let i = minBin + 1; i <= maxBin; i++) {
					if (audioFrequencies[i] > audioFrequencies[peakBin]) peakBin = i;
				}
				const lower = audioFrequencies[peakBin - 1];
				const center = audioFrequencies[peakBin];
				const upper = audioFrequencies[Math.min(peakBin + 1, audioFrequencies.length - 1)];
				const divisor = lower - 2 * center + upper;
				const offset = divisor ? Math.max(-0.5, Math.min(0.5, 0.5 * (lower - upper) / divisor)) : 0;
				const measuredPitch = (peakBin + offset) * binHz;
				if (audioLevel > 0.035 && center > 0) {
					audioPitch += (measuredPitch - audioPitch) * (1 - Math.exp(-delta * 5));
				}
			}
		} else {
			audioLevel *= Math.exp(-delta * 10);
			for (let i = 0; i < audioBands.length; i++) audioBands[i] *= Math.exp(-delta * 8);
		}
		if (visual) {
			visual.draw(t, reducedMotion.matches ? 'idle' : state, reducedMotion.matches, audioLevel, audioPitch, audioBands);
		} else if (ctx) {
			const w = canvas.width, h = canvas.height;
			ctx.clearRect(0, 0, w, h);
			const target = reducedMotion.matches ? 0 : state === 'speaking' ? audioLevel : 0;
			level += (target - level) * (1 - Math.exp(-delta * 12));
			const radius = Math.min(w, h) * 0.39;
			const time = reducedMotion.matches ? 0 : t / 1000;
			const hue = 195 + Math.max(0, Math.min(1, (audioPitch - 85) / 335)) * 45;
			ctx.save();
			ctx.translate(w / 2, h / 2);
			const glow = ctx.createRadialGradient(0, 0, radius * 0.1, 0, 0, radius * 1.24);
			glow.addColorStop(0, `hsla(${hue}, 90%, 65%, ${0.22 + level * 0.15})`);
			glow.addColorStop(0.65, `hsla(${hue}, 90%, 45%, 0.16)`);
			glow.addColorStop(1, 'transparent');
			ctx.fillStyle = glow;
			ctx.beginPath();
			ctx.arc(0, 0, radius * 1.24, 0, Math.PI * 2);
			ctx.fill();
			for (let ring = 0; ring < 3; ring++) {
				ctx.beginPath();
				ctx.strokeStyle = `hsla(${hue + ring * 7}, 90%, ${72 - ring * 8}%, ${0.65 - ring * 0.15})`;
				ctx.lineWidth = ring === 0 ? 2 : 1;
				ctx.setLineDash(ring === 1 ? [5, 6] : ring === 2 ? [36, 12] : []);
				ctx.lineDashOffset = time * (ring === 1 ? -5 : 3);
				ctx.arc(0, 0, radius * (0.65 + ring * 0.16), 0, Math.PI * 2);
				ctx.stroke();
			}
			ctx.setLineDash([]);
			for (let i = 0; i < 80; i++) {
				const angle = i * Math.PI * 2 / 80;
				const length = i % 5 === 0 ? 9 : 4;
				ctx.strokeStyle = `hsla(${hue}, 95%, 80%, ${i % 5 === 0 ? 0.7 : 0.3})`;
				ctx.beginPath();
				ctx.moveTo(Math.cos(angle) * radius * 0.91, Math.sin(angle) * radius * 0.91);
				ctx.lineTo(Math.cos(angle) * (radius * 0.91 + length), Math.sin(angle) * (radius * 0.91 + length));
				ctx.stroke();
			}
			ctx.beginPath();
			ctx.strokeStyle = `hsla(${hue}, 100%, 83%, 0.9)`;
			ctx.lineWidth = 2;
			for (let i = 0; i <= 160; i++) {
				const x = (i / 160 * 2 - 1) * radius * 0.55;
				const envelope = Math.sin(i / 160 * Math.PI);
				const y = Math.sin(x * 0.095 - time * 3) * envelope * (3 + level * radius * 0.22);
				if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
			}
			ctx.stroke();
			ctx.restore();
		}
		if (!reducedMotion.matches) frame = requestAnimationFrame(draw);
	}

	function requestDraw() {
		if (visible && frame === null) frame = requestAnimationFrame(draw);
	}

	const voiceObserver = new IntersectionObserver(([entry]) => {
		visible = entry.isIntersecting;
		if (visible) requestDraw();
		else if (frame !== null) {
			cancelAnimationFrame(frame);
			frame = null;
			lastFrame = 0;
		}
	});
	voiceObserver.observe(document.getElementById('home'));
	reducedMotion.addEventListener('change', requestDraw);
	requestDraw();

	import('https://cdn.jsdelivr.net/npm/three@0.160.1/build/three.module.js')
		.then((THREE) => {
			const renderer = new THREE.WebGLRenderer({ canvas: webglCanvas, alpha: true, antialias: true });
			renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
			const scene = new THREE.Scene();
			const camera = new THREE.Camera();
			const uniforms = {
				time: { value: 0 },
				voice: { value: 0 },
				bands: { value: new THREE.Vector3() },
				tone: { value: 0.3 },
				mode: { value: 0 },
				resolution: { value: new THREE.Vector2(1, 1) }
			};
			const material = new THREE.ShaderMaterial({
				uniforms,
				transparent: true,
				depthWrite: false,
				vertexShader: `
					varying vec2 point;
					void main() {
						point = uv * 2.0 - 1.0;
						gl_Position = vec4(position, 1.0);
					}
				`,
				fragmentShader: `
					varying vec2 point;
					uniform float time, voice, tone, mode;
					uniform vec3 bands;
					uniform vec2 resolution;

					float ring(float r, float radius, float width) {
						return 1.0 - smoothstep(width, width + 0.006, abs(r - radius));
					}

					void main() {
						vec2 p = point * resolution / min(resolution.x, resolution.y);
						float r = length(p);
						float angle = atan(p.y, p.x);
						float pulse = clamp(voice, 0.0, 1.0);
						vec3 cyan = mix(vec3(0.18, 0.78, 1.0), vec3(0.50, 0.60, 1.0), tone * 0.65);
						vec3 violet = vec3(0.43, 0.30, 0.95);
						vec3 color = vec3(0.0);
						float alpha = 0.0;

						float body = 1.0 - smoothstep(0.57, 0.61, r);
						float glow = exp(-r * r * 5.5) * (1.0 - smoothstep(0.76, 0.98, r));
						float flow = sin(p.x * 15.0 + sin(p.y * 9.0 + time * 0.35) * 2.0 - time * 0.55);
						flow += sin(p.y * 17.0 - p.x * 5.0 + time * 0.4) * 0.5;
						float shimmer = 0.5 + 0.5 * sin(r * 23.0 - time * 0.7 + flow + bands.x * 2.0);
						color += mix(vec3(0.025, 0.09, 0.22), cyan * 0.38, shimmer * 0.5 + pulse * 0.25) * body;
						color += cyan * glow * (0.25 + pulse * 0.25);
						alpha += body * 0.7 + glow * 0.3;

						float core = exp(-r * r * 28.0) * (0.30 + pulse * 0.5);
						color += mix(cyan, vec3(0.83, 0.96, 1.0), 0.45) * core;
						alpha += core * 0.3;

						float rim = ring(r, 0.61, 0.004);
						float inner = ring(r, 0.51, 0.002) * (0.4 + 0.6 * step(0.25, fract((angle + time * 0.06) * 3.82)));
						float outer = ring(r, 0.76, 0.002) * (0.25 + 0.75 * step(0.35, fract((angle - time * 0.08) * 1.91)));
						float ticks = (1.0 - smoothstep(0.006, 0.035, abs(r - 0.83)))
							* step(0.91, fract((angle + 3.141593) * 12.73));
						float markers = ring(r, 0.71, 0.006)
							* step(0.87, fract((angle - time * 0.04) * 0.955));
						float instruments = rim * 0.75 + inner * 0.45 + outer * 0.7 + ticks * 0.55 + markers * 0.7;
						color += cyan * instruments * (0.8 + pulse * 0.35);
						alpha += instruments * 0.7;

						float waveMask = (1.0 - smoothstep(0.37, 0.49, abs(p.x))) * body;
						float envelope = max(0.0, 1.0 - abs(p.x) / 0.49);
						float wave = sin(p.x * 20.0 - time * 2.4) * (0.012 + pulse * 0.085 + bands.y * 0.025) * envelope;
						float waveLine = (1.0 - smoothstep(0.003, 0.014, abs(p.y - wave))) * waveMask;
						color += mix(cyan, vec3(0.92, 0.98, 1.0), 0.55) * waveLine;
						alpha += waveLine * 0.8;

						float span = resolution.x / min(resolution.x, resolution.y);
						float side = smoothstep(0.93, 1.04, abs(p.x)) * (1.0 - smoothstep(span - 0.12, span - 0.02, abs(p.x)));
						float rail = exp(-abs(p.y) * 170.0) * side;
						float sideTicks = (1.0 - smoothstep(0.012, 0.04, abs(p.y)))
							* step(0.94, fract(abs(p.x) * 14.0)) * side;
						float meterHeight = (0.04 + 0.045 * (0.5 + 0.5 * sin(abs(p.x) * 13.0 - time * 0.7))
							+ pulse * 0.12 + bands.y * 0.05) * side;
						float meters = (1.0 - smoothstep(meterHeight, meterHeight + 0.008, abs(p.y)))
							* step(0.87, fract(abs(p.x) * 11.0)) * side;
						color += mix(cyan, violet, tone * 0.3) * (rail * 0.65 + sideTicks * 0.5 + meters * 0.5);
						alpha += rail * 0.65 + sideTicks * 0.5 + meters * (0.45 + pulse * 0.2);
						color += violet * glow * (mode * 0.12 + bands.z * 0.18);
						gl_FragColor = vec4(clamp(color, 0.0, 1.0), clamp(alpha, 0.0, 1.0));
					}
				`
			});
			const geometry = new THREE.PlaneGeometry(2, 2);
			scene.add(new THREE.Mesh(geometry, material));

			function resize() {
				const width = webglCanvas.clientWidth;
				const height = webglCanvas.clientHeight;
				if (!width || !height) return;
				renderer.setSize(width, height, false);
				uniforms.resolution.value.set(width, height);
				requestDraw();
			}

			visual = {
				draw(t, currentState, still, signal, pitch, frequencyBands) {
					uniforms.time.value = still ? 0 : t * 0.001;
					uniforms.voice.value = currentState === 'speaking' ? signal : 0;
					uniforms.bands.value.set(...(currentState === 'speaking' ? frequencyBands : [0, 0, 0]));
					uniforms.tone.value = currentState === 'speaking' ? Math.max(0, Math.min(1, (pitch - 85) / 335)) : 0.3;
					uniforms.mode.value = currentState === 'thinking' || currentState === 'listening' ? 1 : 0;
					renderer.render(scene, camera);
				}
			};
			canvas.hidden = true;
			webglCanvas.hidden = false;
			const resizeObserver = new ResizeObserver(resize);
			resizeObserver.observe(webglCanvas);
			webglCanvas.addEventListener('webglcontextlost', () => {
				console.warn('WebGL-Kontext verloren; die einfache Sprachvisualisierung wird verwendet.');
				resizeObserver.disconnect();
				visual = null;
				webglCanvas.hidden = true;
				canvas.hidden = false;
				geometry.dispose();
				material.dispose();
				renderer.dispose();
				requestDraw();
			}, { once: true });
			resize();
		})
		.catch((err) => {
			console.warn('Three.js-Animation nicht verfügbar; die einfache Sprachvisualisierung wird verwendet.', err);
		});

	async function csrfToken(signal) {
		await fetch('/ai-expert/api/csrf', { credentials: 'same-origin', signal });
		return decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || '');
	}

	function stopSpeech() {
		if (!activeSpeech) return;
		activeSpeech.cancelled = true;
		activeSpeech.controller.abort();
		activeSpeech.closeInput?.();
		activeSpeech.finish?.();
		activeSpeech.player?.pause();
	}

	function stopTurn() {
		++chatTurn;
		activeTurn?.controller.abort();
		activeTurn?.closeSpeech();
		const userIndex = history.indexOf(activeTurn?.userMessage);
		if (userIndex !== -1) history.splice(userIndex, 1);
		activeTurn = null;
		stopSpeech();
	}

	/** Spielt kurze Textabschnitte direkt ab und lädt den jeweils nächsten Abschnitt parallel vor. */
	async function speak(reply, turnId = null, closeInput = null) {
		const playback = { controller: new AbortController(), player: null, finish: null, cancelled: false, turnId, closeInput };
		activeSpeech = playback;
		const urls = [];
		let source = null;
		let playbackAnalyser = null;
		async function* speechChunks(input) {
			const sourceText = typeof input === 'string'
				? (async function* () { yield input; })()
				: input;
			let buffer = '';
			for await (const textChunk of sourceText) {
				buffer += textChunk;
				while (true) {
					const sentenceEnd = /[.!?…](?:["'”’)\]]*)\s+/g;
					let match;
					let cut = 0;
					while ((match = sentenceEnd.exec(buffer))) {
						const end = match.index + match[0].trimEnd().length;
						if (end >= 50) {
							cut = end;
							break;
						}
					}
					if (!cut && buffer.length >= 100) {
						cut = buffer.lastIndexOf(' ', 100);
						if (cut < 50) cut = buffer.indexOf(' ', 100);
						if (cut < 0) cut = Math.min(buffer.length, 100);
					}
					if (!cut) break;
					const chunk = plainMarkdown(buffer.slice(0, cut));
					buffer = buffer.slice(cut);
					if (chunk) yield chunk;
				}
			}
			const lastChunk = plainMarkdown(buffer);
			if (lastChunk) yield lastChunk;
		}
		const fetchChunk = async (input) => {
			const token = await csrfToken(playback.controller.signal);
			playback.controller.signal.throwIfAborted();
			const res = await fetch('/ai-expert/api/ki/audio/speech', {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
				body: JSON.stringify({ input }),
				signal: playback.controller.signal
			});
			if (!res.ok) throw new Error(res.status);
			const mimeType = res.headers.get('Content-Type')?.split(';')[0] || 'audio/mpeg';
			const MediaSourceType = window.MediaSource;
			if (!res.body || !MediaSourceType?.isTypeSupported(mimeType)) {
				const blob = await res.blob();
				playback.controller.signal.throwIfAborted();
				const url = URL.createObjectURL(blob);
				urls.push(url);
				return { player: new Audio(url), streamDone: Promise.resolve() };
			}

			const mediaSource = new MediaSourceType();
			const url = URL.createObjectURL(mediaSource);
			urls.push(url);
			const player = new Audio(url);
			await new Promise((resolve, reject) => {
				const signal = playback.controller.signal;
				const cleanup = () => {
					mediaSource.removeEventListener('sourceopen', onOpen);
					mediaSource.removeEventListener('error', onError);
					signal.removeEventListener('abort', onAbort);
				};
				const onOpen = () => { cleanup(); resolve(); };
				const onError = () => { cleanup(); reject(new Error('Audio stream unavailable')); };
				const onAbort = () => { cleanup(); reject(signal.reason); };
				mediaSource.addEventListener('sourceopen', onOpen);
				mediaSource.addEventListener('error', onError);
				signal.addEventListener('abort', onAbort, { once: true });
				if (signal.aborted) onAbort();
			});
			playback.controller.signal.throwIfAborted();
			const sourceBuffer = mediaSource.addSourceBuffer(mimeType);
			const reader = res.body.getReader();
			let resolveReady;
			let rejectReady;
			let firstChunk = true;
			const ready = new Promise((resolve, reject) => {
				resolveReady = resolve;
				rejectReady = reject;
			});
			const streamDone = (async () => {
				try {
					while (true) {
						const { value, done } = await reader.read();
						if (done || playback.cancelled) break;
						await new Promise((resolve, reject) => {
							const onUpdateEnd = () => {
								sourceBuffer.removeEventListener('error', onError);
								resolve();
							};
							const onError = () => {
								sourceBuffer.removeEventListener('updateend', onUpdateEnd);
								reject(new Error('Audio stream could not be decoded'));
							};
							sourceBuffer.addEventListener('updateend', onUpdateEnd, { once: true });
							sourceBuffer.addEventListener('error', onError, { once: true });
							try {
								sourceBuffer.appendBuffer(value);
							} catch (error) {
								sourceBuffer.removeEventListener('updateend', onUpdateEnd);
								sourceBuffer.removeEventListener('error', onError);
								reject(error);
							}
						});
						if (firstChunk) {
							firstChunk = false;
							resolveReady();
						}
					}
					if (firstChunk && playback.cancelled) {
						firstChunk = false;
						resolveReady();
					}
					if (firstChunk && !playback.cancelled) throw new Error('Empty audio stream');
					if (mediaSource.readyState === 'open' && !sourceBuffer.updating) mediaSource.endOfStream();
				} catch (error) {
					if (firstChunk) rejectReady(error);
					throw error;
				}
			})();
			streamDone.catch(() => { });
			await ready;
			return { player, streamDone };
		};
		const chunks = speechChunks(reply)[Symbol.asyncIterator]();
		const nextLoad = async () => {
			const { value, done } = await chunks.next();
			if (done || playback.cancelled) return null;
			const promise = fetchChunk(value);
			promise.catch(() => { });
			return { promise };
		};
		try {
			const AudioContextType = window.AudioContext || window.webkitAudioContext;
			if (AudioContextType) {
				try {
					audioContext ||= new AudioContextType();
					if (audioContext.state === 'suspended') await audioContext.resume();
					if (playback.cancelled) return;
					playbackAnalyser = audioContext.createAnalyser();
					playbackAnalyser.fftSize = 2048;
					playbackAnalyser.smoothingTimeConstant = .3;
					playbackAnalyser.connect(audioContext.destination);
					audioAnalyser = playbackAnalyser;
					audioSamples = new Uint8Array(playbackAnalyser.fftSize);
					audioFrequencies = new Uint8Array(playbackAnalyser.frequencyBinCount);
				} catch (error) {
					playbackAnalyser?.disconnect();
					if (playbackAnalyser && audioAnalyser === playbackAnalyser) {
						audioAnalyser = null;
						audioSamples = null;
						audioFrequencies = null;
					}
					playbackAnalyser = null;
					console.warn('Audioanalyse nicht verfügbar:', error);
				}
			}
			let load = await nextLoad();
			while (load && !playback.cancelled) {
				const { player, streamDone } = await load.promise;
				if (playback.cancelled) break;
				playback.player = player;
				if (playbackAnalyser) {
					try {
						source = audioContext.createMediaElementSource(player);
						source.connect(playbackAnalyser);
					} catch (error) {
						source = null;
						console.warn('Audioanalyse nicht verfügbar:', error);
					}
				}
				const finished = new Promise((resolve, reject) => {
					playback.finish = resolve;
					player.addEventListener('ended', resolve, { once: true });
					player.addEventListener('error', () => reject(new Error('Audio playback failed')), { once: true });
				});
				player.addEventListener('playing', () => {
					if (!playback.cancelled && (turnId === null || turnId === chatTurn)) state = 'speaking';
				}, { once: true });
				await player.play();
				if (playback.cancelled) {
					player.pause();
					break;
				}
				const followingLoad = nextLoad();
				followingLoad.catch(() => { });
				await Promise.all([finished, streamDone]);
				playback.finish = null;
				source?.disconnect();
				source = null;
				load = await followingLoad;
			}
		} catch (error) {
			if (!playback.cancelled && error.name !== 'AbortError') throw error;
		} finally {
			if (playback.cancelled) await chunks.return?.();
			playback.finish = null;
			source?.disconnect();
			playbackAnalyser?.disconnect();
			if (audioAnalyser === playbackAnalyser) {
				audioAnalyser = null;
				audioSamples = null;
				audioFrequencies = null;
			}
			if (activeSpeech === playback) {
				activeSpeech = null;
				if (state === 'speaking' && (turnId === null || turnId === chatTurn)) state = 'idle';
			}
			urls.forEach((url) => URL.revokeObjectURL(url));
			requestDraw();
		}
	}

	function renderWelcome(markdown) {
		text.replaceChildren();
		const assistant = document.createElement('div');
		assistant.className = 'chat-turn chat-assistant';
		const label = document.createElement('strong');
		label.textContent = 'KI';
		assistant.append(label);
		renderMarkdown(assistant, markdown, true);
		text.append(assistant);
	}

	async function playWelcome() {
		const turnId = chatTurn;
		start.disabled = true;
		let welcome;
		try {
			const res = await fetch('/ai-expert/api/ki/welcome', { credentials: 'same-origin' });
			if (!res.ok) throw new Error(res.status);
			({ gespraechId, text: welcome } = await res.json());
		} catch (e) {
			console.error('Begrüßung konnte nicht geladen werden:', e);
			text.textContent = 'Die Begrüßung konnte gerade nicht geladen werden. Bitte versuche es erneut.';
			start.disabled = false;
			return;
		}
		history.push({ role: 'assistant', content: welcome });
		start.hidden = true;
		renderWelcome(welcome);
		choiceReady = true;
		try {
			await speak(plainMarkdown(welcome), turnId);
		} catch (e) {
			if (turnId !== chatTurn) return;
			const speechError = document.createElement('p');
			speechError.className = 'chat-status';
			speechError.textContent = 'Die Sprachausgabe ist fehlgeschlagen.';
			text.querySelector('.chat-assistant')?.append(speechError);
		} finally {
			if (turnId === chatTurn) mic.disabled = !canRecord;
		}
	}

	start.addEventListener('click', playWelcome);

	function closePrivacyPopup() {
		if (privacyPopup.hidden) return;
		const wasOpen = privacyPopup.classList.contains('is-open');
		if (privacyOpenFrame !== null) {
			cancelAnimationFrame(privacyOpenFrame);
			privacyOpenFrame = null;
		}
		privacy.setAttribute('aria-expanded', 'false');
		privacy.focus();
		privacyPopup.classList.remove('is-open');
		if (reducedMotion.matches || !wasOpen) {
			privacyPopup.hidden = true;
			return;
		}
		privacyCloseTimer = setTimeout(() => {
			if (privacy.getAttribute('aria-expanded') === 'false') privacyPopup.hidden = true;
		}, 220);
	}

	privacy.addEventListener('click', (event) => {
		event.stopPropagation();
		if (!privacyPopup.hidden) {
			closePrivacyPopup();
			return;
		}
		clearTimeout(privacyCloseTimer);
		privacyPopup.hidden = false;
		privacy.setAttribute('aria-expanded', 'true');
		privacyOpenFrame = requestAnimationFrame(() => {
			privacyOpenFrame = null;
			if (privacy.getAttribute('aria-expanded') === 'true') privacyPopup.classList.add('is-open');
		});
		privacyPopup.focus();
	});
	let privacyOpenFrame = null;
	let privacyCloseTimer = null;
	privacyPopup.addEventListener('transitionend', (event) => {
		if (event.target === privacyPopup && event.propertyName === 'opacity'
			&& privacy.getAttribute('aria-expanded') === 'false') {
			clearTimeout(privacyCloseTimer);
			privacyPopup.hidden = true;
		}
	});
	privacyPopup.addEventListener('click', closePrivacyPopup);
	document.addEventListener('click', (event) => {
		if (!privacyPopup.hidden && !privacyPopup.contains(event.target) && event.target !== privacy)
			closePrivacyPopup();
	});
	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape') closePrivacyPopup();
	});

	function submitChatAnswer(answer, auswahl = null) {
		if (!choiceReady || state === 'listening' || !String(answer).trim()) return;
		const value = String(answer).trim();
		const ausMultipleChoice = Boolean(text.querySelector('.chat-choices'));
		choiceReady = false;
		stopTurn();
		ask(value, auswahl, ausMultipleChoice);
	}

	text.addEventListener('click', (event) => {
		const button = event.target.closest('[data-chat-choice]');
		if (button && !button.disabled && choiceReady && state !== 'listening') {
			const group = button.closest('.chat-choices');
			if (group?.dataset.selectionMode === 'multiple') {
				const selected = button.getAttribute('aria-pressed') === 'true';
				const count = group.querySelectorAll('[data-chat-choice][aria-pressed="true"]').length;
				if (!selected && count >= Number(group.dataset.maxSelections)) return;
				button.setAttribute('aria-pressed', String(!selected));
				group.querySelector('[data-chat-submit-multiple]').disabled = group
					.querySelectorAll('[data-chat-choice][aria-pressed="true"]').length === 0;
				return;
			}
			submitChatAnswer(button.dataset.chatChoice, [button.dataset.chatChoice]);
			return;
		}
		const submit = event.target.closest('[data-chat-submit-multiple]');
		if (submit && !submit.disabled) {
			const group = submit.closest('.chat-choices');
			const answers = [...group.querySelectorAll('[data-chat-choice][aria-pressed="true"]')]
				.map(option => option.dataset.chatChoice);
			submitChatAnswer(answers.join('\n'), answers);
		}
	});
	text.addEventListener('submit', (event) => {
		const form = event.target.closest('[data-chat-text-form]');
		if (!form) return;
		event.preventDefault();
		submitChatAnswer(form.elements.answer.value);
	});

	let aktionenAngezeigt = false;

	function zeigeZusammenfassung(summary) {
		const bookingForm = document.getElementById('booking-form');
		const nachricht = bookingForm?.elements.nachricht;
		if (nachricht) nachricht.value = summary;
		if (bookingForm) bookingForm.dataset.gespraechId = gespraechId || '';
		const bereich = text.querySelector('.chat-assistant');
		if (!bereich || aktionenAngezeigt) return;
		aktionenAngezeigt = true;
		const aktionen = document.createElement('div');
		aktionen.className = 'chat-actions';
		const termin = document.createElement('button');
		termin.type = 'button';
		termin.className = 'btn-text';
		termin.textContent = 'Termin vereinbaren';
		termin.addEventListener('click', () => {
			termin.remove();
			document.getElementById('kontakt')?.scrollIntoView({
				behavior: reducedMotion.matches ? 'auto' : 'smooth'
			});
		});
		const weiterchatten = document.createElement('button');
		weiterchatten.type = 'button';
		weiterchatten.className = 'btn-text';
		weiterchatten.textContent = 'Mit mir weiterchatten';
		weiterchatten.addEventListener('click', () => {
			document.getElementById('voice-mic').hidden = false;
			aktionen.remove();
			const danke = document.createElement('p');
			danke.className = 'chat-status';
			danke.textContent = 'Mit diesen Hintergrundwissen kann ich nun gezielt auf Deine Fragen antworten. '
				+ 'Klicke hierzu auf das Mikrofon, um sie zu stellen. '
				+ 'Wenn Du fertig bist, klicke es erneut, um die Frage abzusenden.';
			bereich.append(danke);
		});
		aktionen.append(termin, weiterchatten);
		bereich.append(aktionen);
	}

	async function ask(question, auswahl = null, ausMultipleChoice = false) {
		const turnId = ++chatTurn;
		const controller = new AbortController();
		choiceReady = false;
		state = 'thinking';
		const speechItems = [];
		let speechClosed = false;
		let speechWake;
		let speechTask = null;
		async function* speechStream() {
			while (true) {
				if (speechClosed && controller.signal.aborted) {
					return;
				} else if (speechItems.length) {
					yield speechItems.shift();
				} else if (speechClosed) {
					return;
				} else {
					await new Promise((resolve) => { speechWake = resolve; });
				}
			}
		}
		function wakeSpeech() {
			const wake = speechWake;
			speechWake = null;
			wake?.();
		}
		function enqueueSpeech(chunk) {
			if (controller.signal.aborted) return;
			speechItems.push(chunk);
			if (!speechTask) speechTask = speak(speechStream(), turnId, discardSpeech);
			speechTask.catch(() => closeSpeech());
			wakeSpeech();
		}
		function closeSpeech() {
			speechClosed = true;
			wakeSpeech();
		}
		function discardSpeech() {
			speechItems.length = 0;
			closeSpeech();
		}
		const turn = { controller, closeSpeech: discardSpeech, userMessage: null };
		activeTurn = turn;
		if (ausMultipleChoice) {
			renderConversation(question, '');
		} else {
			renderConversation(question, '', { thinking: true });
			await ladeSprueche();
			if (turnId !== chatTurn) return;
			renderConversation(question, '', { thinking: true, thinkingQuote: naechsterSpruch() });
		}
		const userMessage = { role: 'user', content: question };
		turn.userMessage = userMessage;
		history.push(userMessage);
		let reply = '';

		function renderReply() {
			renderConversation(question, reply);
		}

		function addText(chunk) {
			reply += chunk;
			enqueueSpeech(chunk);
			renderReply();
		}

		try {
			const token = await csrfToken(controller.signal);
			controller.signal.throwIfAborted();
			const requestBody = JSON.stringify(auswahl?.length
				? { messages: history.slice(-10), gespraechId, auswahl }
				: { messages: history.slice(-10), gespraechId });
			let res = null;
			for (let attempt = 1; attempt <= 3; attempt++) {
				res = await fetch('/ai-expert/api/ki/chat/stream', {
					method: 'POST',
					credentials: 'same-origin',
					headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
					body: requestBody,
					signal: controller.signal
				});
				if (res.status !== 429) break;
				const wartezeitMs = Number(res.headers.get('Retry-After') || 1) * 1000;
				if (attempt === 3 || wartezeitMs > 5000) break;
				await new Promise((resolve) => setTimeout(resolve, wartezeitMs));
				controller.signal.throwIfAborted();
			}
			if (!res.ok) throw new Error(res.status);
			gespraechId = res.headers.get('X-Chat-Id') || gespraechId;
			if (!res.body) throw new Error('Streaming wird nicht unterstützt');
			const reader = res.body.getReader();
			const decoder = new TextDecoder();
			let buffer = '';
			let completed = false;
			let zusammenfassung = '';
			try {
				while (!completed) {
					const { value, done } = await reader.read();
					controller.signal.throwIfAborted();
					buffer += decoder.decode(value, { stream: !done });
					let boundary;
					while ((boundary = buffer.indexOf('\n\n')) !== -1) {
						const event = buffer.slice(0, boundary);
						buffer = buffer.slice(boundary + 2);
						const lines = event.split('\n');
						const type = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();
						const data = lines.filter((line) => line.startsWith('data:'))
							.map((line) => line.slice(5).trimStart()).join('\n');
						if (!data) continue;
						const payload = JSON.parse(data);
						if (type === 'delta') addText(payload.text);
						if (type === 'error') throw new Error(payload.text);
						if (type === 'done') {
							completed = true;
							zusammenfassung = typeof payload.zusammenfassung === 'string' ? payload.zusammenfassung : '';
							if (typeof payload.text === 'string' && payload.text.trim()) {
								const finalReply = payload.text.trim();
								if (!reply.trim()) enqueueSpeech(finalReply);
								else if (finalReply.startsWith(reply.trim())) enqueueSpeech(finalReply.slice(reply.trim().length));
								reply = finalReply;
							}
							break;
						}
					}
					if (done && !completed) throw new Error('Die KI-Antwort wurde unterbrochen.');
				}
			} finally {
				if (!completed) await reader.cancel();
				reader.releaseLock();
			}
			if (!reply.trim()) throw new Error('Die KI hat keine Antwort geliefert.');
			controller.signal.throwIfAborted();
			history.push({ role: 'assistant', content: reply.trim() });
			renderConversation(question, reply.trim(), { choices: true });
			choiceReady = true;
			if (zusammenfassung) zeigeZusammenfassung(zusammenfassung);
			closeSpeech();
			if (activeTurn?.controller === controller) activeTurn = null;
			try {
				if (!speechTask) speechTask = speak(plainMarkdown(reply), turnId);
				await speechTask;
			} catch (e) {
				if (turnId !== chatTurn) return;
				const speechError = document.createElement('p');
				speechError.className = 'chat-status';
				speechError.textContent = 'Die Sprachausgabe ist fehlgeschlagen.';
				text.querySelector('.chat-assistant')?.append(speechError);
			}
			if (turnId === chatTurn) {
				state = 'idle';
				mic.disabled = !canRecord;
			}
		} catch (e) {
			closeSpeech();
			if (activeSpeech?.turnId === turnId) stopSpeech();
			const userIndex = history.indexOf(userMessage);
			if (userIndex !== -1) history.splice(userIndex, 1);
			if (activeTurn?.controller === controller) activeTurn = null;
			await speechTask?.catch(() => { });
			if (turnId !== chatTurn) return;
			state = 'idle';
			mic.disabled = !canRecord;
			console.error('KI-Antwort fehlgeschlagen:', e);
			const message = e.message === '429' ? 'Bitte einen Moment warten und erneut versuchen.' : 'Die KI-Antwort konnte nicht vollständig geladen werden.';
			renderConversation(question, reply, { status: message });
		}
	}

	mic.addEventListener('click', async () => {
		if (!canRecord) return;
		if (recorder?.state === 'recording') {
			recorder.stop();
			return;
		}
		choiceReady = false;
		stopTurn();
		try {
			const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
			const mimeType = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/ogg;codecs=opus']
				.find((type) => MediaRecorder.isTypeSupported(type));
			const currentRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
			const chunks = [];
			recorder = currentRecorder;
			currentRecorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
			currentRecorder.onstop = async () => {
				stream.getTracks().forEach((track) => track.stop());
				mic.classList.remove('rec');
				mic.setAttribute('aria-label', 'Sprechen');
				mic.title = 'Frage aufnehmen';
				recorder = null;
				const audio = new Blob(chunks, { type: currentRecorder.mimeType || 'audio/webm' });
				if (!audio.size) {
					state = 'idle';
					text.textContent = 'Keine Sprache aufgenommen.';
					return;
				}
				state = 'thinking';
				mic.disabled = true;
				text.textContent = 'Sprache wird erkannt …';
				try {
					const token = await csrfToken();
					const form = new FormData();
					const extension = audio.type.includes('mp4') ? 'mp4' : audio.type.includes('ogg') ? 'ogg' : 'webm';
					form.append('audio', audio, 'recording.' + extension);
					const res = await fetch('/ai-expert/api/ki/audio/transcriptions', {
						method: 'POST', credentials: 'same-origin',
						headers: { 'X-XSRF-TOKEN': token }, body: form
					});
					if (!res.ok) throw new Error(res.status);
					const transcription = await res.json();
					if (!transcription.text?.trim()) throw new Error('Keine Sprache erkannt');
					await ask(transcription.text.trim());
				} catch (e) {
					state = 'idle';
					mic.disabled = !canRecord;
					text.textContent = 'Sprache konnte nicht erkannt werden. Bitte erneut versuchen.';
					console.error('Transkription fehlgeschlagen:', e);
				}
			};
			currentRecorder.start();
			state = 'listening';
			mic.classList.add('rec');
			mic.setAttribute('aria-label', 'Aufnahme beenden');
			mic.title = 'Aufnahme beenden';
			text.textContent = 'Ich höre zu. Drücke erneut, um die Aufnahme zu beenden.';
		} catch (e) {
			state = 'idle';
			text.textContent = 'Mikrofonzugriff nicht möglich.';
			console.error('Mikrofonzugriff fehlgeschlagen:', e);
		}
	});

})();
