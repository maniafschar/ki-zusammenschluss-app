package de.kiexperten.audit;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Items may only be created by experts with the role ADMIN, not by the configured superuser account. */
@Component
public class CreateGuard {
	private final String superuser;

	public CreateGuard(@Value("${app.admin.username}") final String superuser) {
		this.superuser = superuser;
	}

	public boolean allowed(final Principal principal) {
		return principal instanceof final Authentication auth && !this.superuser.equals(auth.getName())
				&& auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
	}

	public boolean allowedForInteressent(final Principal principal) {
		return principal instanceof final Authentication auth && !this.superuser.equals(auth.getName())
				&& auth.getAuthorities().stream()
						.anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_BERATER"));
	}

	public void require(final Principal principal) {
		if (!allowed(principal)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Nur Benutzer mit der Rolle ADMIN dürfen Einträge anlegen.");
		}
	}

	public void requireInteressent(final Principal principal) {
		if (!allowedForInteressent(principal)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Nur Benutzer mit der Rolle ADMIN oder BERATER dürfen Interessenten anlegen.");
		}
	}
}
