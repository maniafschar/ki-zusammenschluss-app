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
					selectedDay = key;
					selectedSlot = null;
					bookingForm.hidden = true;
					renderCalendar();
					renderSlots();
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
					`Ihr Termin: ${day.toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' })}, ${timeOf(s.start)} Uhr (30 Minuten)`;
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
					nachricht: bookingForm.elements.nachricht.value
				})
			});
			if (res.status === 409) {
				setMessage('Dieser Termin wurde gerade vergeben. Bitte wählen Sie einen anderen.', 'error');
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
			el('slots').innerHTML = '<p class="booking-hint ok">Wir haben Ihnen eine E-Mail mit einem Bestätigungslink geschickt. '
				+ 'Bitte bestätigen Sie Ihre Adresse innerhalb von 10 Minuten, sonst wird der Termin wieder freigegeben.</p>';
		} catch (err) {
			setMessage('Die Buchung hat nicht geklappt. Bitte versuchen Sie es erneut.', 'error');
			console.error(err);
		} finally {
			submit.disabled = false;
		}
	});

	loadSlots().then(() => {
		const result = new URLSearchParams(window.location.search).get('termin');
		const texts = {
			bestaetigt: ['Vielen Dank! Ihr Termin ist bestätigt.', 'ok'],
			abgelaufen: ['Der Bestätigungslink ist abgelaufen. Bitte buchen Sie erneut.', 'error'],
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
	const canvas = document.getElementById('voice-canvas');
	const webglCanvas = document.getElementById('voice-webgl');
	const ctx = canvas.getContext('2d');
	const canRecord = Boolean(window.MediaRecorder && navigator.mediaDevices?.getUserMedia);
	const welcome = 'Willkommen beim KI Experten Team! Wir sind ein unabhängiges Team von top Spezialisten, jeder auf seinem Gebiet. Uns alle vereint die Art, wie wir denken das KI in Unternehmen den größten Profit erbringt. Du kannst mir gerne Fragen stellen oder einfach sagen, dass ich Dir mehr über uns erzählen soll...';
	if (!canRecord) {
		mic.disabled = true;
		mic.title = 'Audioaufnahme wird von diesem Browser nicht unterstützt';
	}
	const history = [];
	let gespraechId = null;
	let state = 'idle'; // idle | listening | thinking | speaking
	let recorder = null;
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
			const w = canvas.width, h = canvas.height, bars = 32;
			ctx.clearRect(0, 0, w, h);
			const target = reducedMotion.matches ? 0.15 : state === 'speaking' ? audioLevel : state === 'thinking' ? 0.25 : state === 'listening' ? 0.12 : 0.05;
			level += (target - level) * (1 - Math.exp(-delta * 12));
			const fallbackHue = 235 - Math.max(0, Math.min(1, (audioPitch - 85) / 335)) * 205;
			ctx.fillStyle = `hsl(${fallbackHue} 95% 70%)`;
			for (let i = 0; i < bars; i++) {
				const wave = Math.sin((reducedMotion.matches ? 0 : t / 220) + i * 0.5) * 0.5 + 0.5;
				const bh = Math.max(6, h * (0.15 + 0.85 * wave) * level * (1 - Math.abs(i - bars / 2) / bars));
				const x = (i + 0.5) * (w / bars);
				ctx.beginPath();
				ctx.roundRect(x - 8, (h - bh) / 2, 16, bh, 8);
				ctx.fill();
			}
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
			const camera = new THREE.OrthographicCamera(-3, 3, 3, -3, 0.1, 100);
			camera.position.z = 6;
			const uniforms = {
				time: { value: 0 },
				voice: { value: 0 },
				bands: { value: new THREE.Vector3() },
				tone: { value: 0.3 },
				mode: { value: 0 }
			};
			const material = new THREE.ShaderMaterial({
				uniforms,
				transparent: true,
				depthWrite: false,
				vertexShader: `
					varying vec2 point;
					void main() {
						point = uv * 2.0 - 1.0;
						gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
					}
				`,
				fragmentShader: `
					varying vec2 point;
					uniform float time, voice, tone, mode;
					uniform vec3 bands;

					float noise(vec2 p) {
						return sin(p.x) * sin(p.y);
					}

					void main() {
						float r = length(point);
						float angle = atan(point.y, point.x);
						float drift = time * 0.24;
						float movement = 0.012 + voice * 0.075;
						float edge = 0.48 + voice * 0.055
							+ movement * (0.55 * sin(angle * 3.0 + drift)
							+ 0.3 * sin(angle * 5.0 - drift * 1.3 + bands.y * 2.0)
							+ 0.15 * sin(angle * 9.0 + drift + bands.z * 3.0));
						float body = 1.0 - smoothstep(edge - 0.045, edge + 0.045, r);
						float halo = exp(-max(r - edge, 0.0) * 9.0) * 0.22;
						float rim = exp(-abs(r - edge) * 35.0) * (0.22 + voice * 0.3);
						float flow = noise(point * 9.0 + vec2(drift, -drift * 0.7));
						flow += 0.5 * noise(point.yx * 17.0 + vec2(-drift * 1.4, drift));
						float veil = 0.5 + 0.5 * sin(r * 19.0 - drift * 2.0 + flow * 1.6 + bands.x * 2.0);
						vec3 deep = mix(vec3(0.06, 0.19, 0.50), vec3(0.34, 0.12, 0.52), tone);
						vec3 light = mix(vec3(0.23, 0.80, 0.95), vec3(1.0, 0.42, 0.69), tone);
						vec3 color = deep * (0.45 + veil * 0.35) + light * (rim + body * (0.15 + veil * 0.2));
						color += vec3(0.55, 0.85, 1.0) * exp(-r * 5.5) * (0.45 + voice * 0.7);
						color += light * halo * (0.65 + mode * 0.2);
						float alpha = clamp(body * 0.85 + halo + rim, 0.0, 1.0);
						gl_FragColor = vec4(color, alpha);
					}
				`
			});
			const geometry = new THREE.PlaneGeometry(4.6, 4.6);
			scene.add(new THREE.Mesh(geometry, material));

			function resize() {
				const width = webglCanvas.clientWidth;
				const height = webglCanvas.clientHeight;
				if (!width || !height) return;
				renderer.setSize(width, height, false);
				const aspect = width / height;
				const halfHeight = 2.45 / Math.min(aspect, 1);
				camera.left = -halfHeight * aspect;
				camera.right = halfHeight * aspect;
				camera.top = halfHeight;
				camera.bottom = -halfHeight;
				camera.updateProjectionMatrix();
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
			window.addEventListener('resize', resize);
			webglCanvas.addEventListener('webglcontextlost', () => {
				console.warn('WebGL-Kontext verloren; die einfache Sprachvisualisierung wird verwendet.');
				window.removeEventListener('resize', resize);
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

	async function csrfToken() {
		await fetch('/ai-expert/api/csrf', { credentials: 'same-origin' });
		return decodeURIComponent((document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/) || [])[1] || '');
	}

	async function speak(reply) {
		const token = await csrfToken();
		const res = await fetch('/ai-expert/api/ki/audio/speech', {
			method: 'POST',
			credentials: 'same-origin',
			headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
			body: JSON.stringify({ input: reply })
		});
		if (!res.ok) throw new Error(res.status);
		const audioUrl = URL.createObjectURL(await res.blob());
		const player = new Audio(audioUrl);
		let source = null;
		let playbackAnalyser = null;
		const AudioContextType = window.AudioContext || window.webkitAudioContext;
		if (AudioContextType) {
			try {
				audioContext ||= new AudioContextType();
				if (audioContext.state === 'suspended') await audioContext.resume();
				playbackAnalyser = audioContext.createAnalyser();
				playbackAnalyser.fftSize = 2048;
				playbackAnalyser.smoothingTimeConstant = .3;
				source = audioContext.createMediaElementSource(player);
				source.connect(playbackAnalyser);
				playbackAnalyser.connect(audioContext.destination);
				audioAnalyser = playbackAnalyser;
				audioSamples = new Uint8Array(playbackAnalyser.fftSize);
				audioFrequencies = new Uint8Array(playbackAnalyser.frequencyBinCount);
			} catch (error) {
				if (source) {
					try {
						source.disconnect();
						source.connect(audioContext.destination);
					} catch (connectError) {
						console.warn('Audioanalyse nicht verfügbar:', connectError);
					}
				}
				playbackAnalyser?.disconnect();
				audioAnalyser = null;
				audioSamples = null;
				audioFrequencies = null;
				console.warn('Audioanalyse nicht verfügbar:', error);
			}
		}
		try {
			const finished = new Promise((resolve, reject) => {
				player.addEventListener('ended', resolve, { once: true });
				player.addEventListener('error', () => reject(new Error('Audio playback failed')), { once: true });
			});
			player.addEventListener('playing', () => { state = 'speaking'; }, { once: true });
			await player.play();
			await finished;
		} finally {
			source?.disconnect();
			playbackAnalyser?.disconnect();
			if (audioAnalyser === playbackAnalyser) {
				audioAnalyser = null;
				audioSamples = null;
				audioFrequencies = null;
			}
			if (state === 'speaking') state = 'idle';
			URL.revokeObjectURL(audioUrl);
			requestDraw();
		}
	}

	async function playWelcome() {
		text.textContent = welcome;
		start.hidden = true;
		mic.hidden = false;
		mic.disabled = true;
		privacy.hidden = false;
		try {
			await speak(welcome);
		} catch (e) {
			text.textContent = welcome + ' Sprachausgabe ist derzeit nicht verfügbar.';
			state = 'idle';
		} finally {
			mic.disabled = !canRecord;
		}
	}

	start.addEventListener('click', playWelcome, { once: true });

	async function ask(question) {
		state = 'thinking';
		mic.disabled = true;
		text.innerHTML = '<b>Sie:</b> ' + esc(question) + '<br>Die KI denkt nach …';
		history.push({ role: 'user', content: question });
		let reply = '';

		function renderReply() {
			const follow = text.scrollHeight - text.scrollTop - text.clientHeight <= 2;
			text.innerHTML = '<b>Sie:</b> ' + esc(question) + '<br><b>KI:</b> ' + esc(reply);
			if (follow) text.scrollTop = text.scrollHeight;
		}

		function addText(chunk) {
			reply += chunk;
			renderReply();
		}

		try {
			const token = await csrfToken();
			const res = await fetch('/ai-expert/api/ki/chat/stream', {
				method: 'POST',
				credentials: 'same-origin',
				headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
				body: JSON.stringify({ messages: history.slice(-10), gespraechId })
			});
			if (!res.ok) throw new Error(res.status);
			gespraechId = res.headers.get('X-Chat-Id') || gespraechId;
			if (!res.body) throw new Error('Streaming wird nicht unterstützt');
			const reader = res.body.getReader();
			const decoder = new TextDecoder();
			let buffer = '';
			let completed = false;
			try {
				while (!completed) {
					const { value, done } = await reader.read();
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
						if (type === 'done') { completed = true; break; }
					}
					if (done && !completed) throw new Error('Die KI-Antwort wurde unterbrochen.');
				}
			} finally {
				if (!completed) await reader.cancel();
				reader.releaseLock();
			}
			if (!reply.trim()) throw new Error('Die KI hat keine Antwort geliefert.');
			history.push({ role: 'assistant', content: reply.trim() });
			try {
				await speak(reply.trim());
			} catch (e) {
				text.innerHTML += '<br>Die Sprachausgabe ist fehlgeschlagen.';
			}
			state = 'idle';
			mic.disabled = !canRecord;
		} catch (e) {
			history.pop();
			state = 'idle';
			mic.disabled = !canRecord;
			console.error('KI-Antwort fehlgeschlagen:', e);
			const message = e.message === '429' ? 'Bitte einen Moment warten und erneut versuchen.' : 'Die KI-Antwort konnte nicht vollständig geladen werden.';
			text.innerHTML = '<b>Sie:</b> ' + esc(question) + (reply ? '<br><b>KI:</b> ' + esc(reply) : '') + '<br>' + esc(message);
		}
	}

	mic.addEventListener('click', async () => {
		if (!canRecord) return;
		if (recorder?.state === 'recording') {
			recorder.stop();
			return;
		}
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
			text.textContent = 'Ich höre zu. Drücken Sie erneut, um die Aufnahme zu beenden.';
		} catch (e) {
			state = 'idle';
			text.textContent = 'Mikrofonzugriff nicht möglich.';
			console.error('Mikrofonzugriff fehlgeschlagen:', e);
		}
	});

})();
