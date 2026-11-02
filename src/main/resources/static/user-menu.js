/* User menu for admin.html and dashboard.html: shows the logged-in user, offers logout and password change. */
(function () {
	const API = '/ai-expert/api';
	const box = document.getElementById('current-user');
	if (!box) return;

	const style = document.createElement('style');
	style.textContent = `
		#current-user { cursor: pointer; background: none; border: 0; font: inherit; color: inherit; padding: 4px 8px; border-radius: 6px; }
		#current-user:hover, #current-user:focus-visible { background: rgba(0,0,0,.12); outline: none; }
		#user-menu { position: fixed; top: 44px; right: 16px; z-index: 1000; width: 280px; background: #fff; color: #1d2b3a;
			border: 1px solid #cbd5e1; border-radius: 10px; box-shadow: 0 8px 24px rgba(0,0,0,.2); padding: 14px; font: 14px system-ui, sans-serif; }
		#user-menu[hidden] { display: none; }
		#user-menu label { display: block; margin: 8px 0; }
		#user-menu input { display: block; width: 100%; box-sizing: border-box; padding: 6px 8px; margin-top: 4px; border: 1px solid #cbd5e1; border-radius: 6px; font: inherit; }
		#user-menu button { padding: 6px 12px; border-radius: 6px; border: 1px solid #cbd5e1; background: #f1f5f9; cursor: pointer; font: inherit; color: inherit; }
		#user-menu .row { display: flex; gap: 8px; margin-top: 10px; }
		#user-menu .msg { min-height: 1.2em; margin: 8px 0 0; font-weight: 600; }
	`;
	document.head.appendChild(style);

	const menu = document.createElement('div');
	menu.id = 'user-menu';
	menu.hidden = true;
	menu.setAttribute('role', 'dialog');
	menu.setAttribute('aria-label', 'Benutzer');
	menu.innerHTML = `
		<strong id="um-name"></strong><div id="um-mail" style="color:#667085"></div>
		<form id="um-form" hidden>
			<label>Aktuelles Passwort<input type="password" name="aktuell" autocomplete="current-password" required></label>
			<label>Neues Passwort (mind. 10 Zeichen)<input type="password" name="neu" minlength="10" autocomplete="new-password" required></label>
			<label>Neues Passwort wiederholen<input type="password" name="neu2" minlength="10" autocomplete="new-password" required></label>
			<button type="submit">Passwort ändern</button>
		</form>
		<p id="um-note" style="color:#667085" hidden>Das Passwort dieses Zugangs wird in der Serverkonfiguration gepflegt.</p>
		<p class="msg" id="um-msg" role="status"></p>
		<div class="row"><button type="button" id="um-logout">Abmelden</button></div>`;
	document.body.appendChild(menu);
	const q = (s) => menu.querySelector(s);

	const getCookie = (name) => decodeURIComponent((document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)')) || [])[1] || '');
	const msg = (text, error) => { const m = q('#um-msg'); m.textContent = text; m.style.color = error ? '#b42318' : '#0f9d7a'; };

	fetch(API + '/admin/me', { credentials: 'same-origin' }).then((r) => r.ok ? r.json() : null).then((me) => {
		if (!me) return;
		box.textContent = '👤 ' + me.name;
		box.setAttribute('role', 'button');
		box.tabIndex = 0;
		q('#um-name').textContent = me.name;
		q('#um-mail').textContent = me.username;
		q('#um-form').hidden = !me.passwortAenderbar;
		q('#um-note').hidden = me.passwortAenderbar;
	}).catch(() => { });

	const toggle = () => { menu.hidden = !menu.hidden; msg(''); };
	box.addEventListener('click', (e) => { e.stopPropagation(); toggle(); });
	box.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } });
	menu.addEventListener('click', (e) => e.stopPropagation());
	document.addEventListener('click', () => { menu.hidden = true; });
	document.addEventListener('keydown', (e) => { if (e.key === 'Escape') menu.hidden = true; });

	q('#um-form').addEventListener('submit', async (e) => {
		e.preventDefault();
		const f = e.target.elements;
		if (f.neu.value !== f.neu2.value) return msg('Die Passwörter stimmen nicht überein.', true);
		try {
			await fetch(API + '/csrf', { credentials: 'same-origin' });
			const r = await fetch(API + '/admin/me/passwort', {
				method: 'POST', credentials: 'same-origin',
				headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': getCookie('XSRF-TOKEN') },
				body: JSON.stringify({ aktuell: f.aktuell.value, neu: f.neu.value })
			});
			if (r.status === 204) {
				e.target.reset();
				msg('Passwort geändert. Beim nächsten Login bitte das neue verwenden.');
			} else {
				const body = await r.json().catch(() => null);
				msg((body && body.message) || 'Fehler ' + r.status, true);
			}
		} catch (err) { msg(err.message, true); }
	});

	// HTTP Basic has no server-side session: overwrite the browser's cached credentials with invalid ones.
	q('#um-logout').addEventListener('click', () => {
		const xhr = new XMLHttpRequest();
		xhr.open('GET', API + '/admin/me', false, 'abgemeldet', 'abgemeldet');
		try { xhr.send(); } catch (err) { /* 401 is expected */ }
		window.location.href = '/ai-expert/';
	});
})();
