package de.kiexperten.config;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.userdetails.UserDetails;

import de.kiexperten.content.Experte;

/** Authenticated expert principal retaining the entity for audit references. */
public final class ExperteUserDetails implements UserDetails, CredentialsContainer {

	private final Experte experte;
	private final UserDetails delegate;

	public ExperteUserDetails(final Experte experte, final UserDetails delegate) {
		this.experte = experte;
		this.delegate = delegate;
	}

	public Experte getExperte() {
		return this.experte;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return this.delegate.getAuthorities();
	}

	@Override
	public String getPassword() {
		return this.delegate.getPassword();
	}

	@Override
	public String getUsername() {
		return this.delegate.getUsername();
	}

	@Override
	public boolean isAccountNonExpired() {
		return this.delegate.isAccountNonExpired();
	}

	@Override
	public boolean isAccountNonLocked() {
		return this.delegate.isAccountNonLocked();
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return this.delegate.isCredentialsNonExpired();
	}

	@Override
	public boolean isEnabled() {
		return this.delegate.isEnabled();
	}

	@Override
	public void eraseCredentials() {
		if (this.delegate instanceof CredentialsContainer credentialsContainer) {
			credentialsContainer.eraseCredentials();
		}
	}
}
