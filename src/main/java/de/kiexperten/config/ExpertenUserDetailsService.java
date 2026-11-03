package de.kiexperten.config;

import java.util.Locale;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import de.kiexperten.content.ExperteRepository;
import de.kiexperten.content.ExpertenRolle;
import de.kiexperten.content.ExpertenRollenService;

/**
 * Experts with the current role ADMIN or BERATER (and a password) may log in; AUSGESCHIEDEN may not. The role is
 * evaluated on every request, so a change takes effect immediately. The configured emergency account is kept for scripts.
 */
class ExpertenUserDetailsService implements UserDetailsService {
	private final UserDetails notzugang;
	private final ExperteRepository experten;
	private final ExpertenRollenService rollen;

	ExpertenUserDetailsService(final UserDetails notzugang, final ExperteRepository experten,
			final ExpertenRollenService rollen) {
		this.notzugang = notzugang;
		this.experten = experten;
		this.rollen = rollen;
	}

	@Override
	public UserDetails loadUserByUsername(final String username) {
		if (this.notzugang.getUsername().equals(username)) {
			// Spring erases credentials of returned objects after login, so hand out a fresh copy
			return User.withUserDetails(this.notzugang).build();
		}
		return this.experten.findByEmailIgnoreCase(username.trim().toLowerCase(Locale.ROOT))
				.filter(e -> e.getPasswortHash() != null)
				.filter(e -> this.rollen.aktuelle(e.getId()) != ExpertenRolle.AUSGESCHIEDEN)
				.map(e -> {
					final UserDetails user = User.withUsername(e.getEmail()).password(e.getPasswortHash())
							.roles(this.rollen.aktuelle(e.getId()).name()).build();
					return new ExperteUserDetails(e, user);
				})
				.orElseThrow(() -> new UsernameNotFoundException("unbekannt"));
	}
}
