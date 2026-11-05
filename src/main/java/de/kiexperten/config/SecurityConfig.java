package de.kiexperten.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import de.kiexperten.content.ExperteRepository;
import de.kiexperten.content.ExpertenRollenService;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
		// Plain handler: the X-XSRF-TOKEN header carries the raw cookie value (SPA
		// setup).
		final CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
		csrfRequestHandler.setCsrfRequestAttributeName(null);

		http.authorizeHttpRequests(auth -> auth
				.requestMatchers("/", "/index.html", "/api/leistungen", "/api/produkte", "/api/experten",
						"/api/csrf", "/api/ki/chat", "/api/termine", "/api/termine/bestaetigen", "/api/r/*", "/error")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/api/admin/termine", "/api/admin/interessenten",
						"/api/admin/interessenten/*")
				.hasAnyRole("ADMIN", "BERATER")
				.requestMatchers(HttpMethod.POST, "/api/admin/interessenten", "/api/admin/interessenten/*/senden")
				.hasAnyRole("ADMIN", "BERATER")
				.requestMatchers(HttpMethod.PUT, "/api/admin/interessenten/*")
				.hasAnyRole("ADMIN", "BERATER")
				.requestMatchers(HttpMethod.DELETE, "/api/admin/termine/*", "/api/admin/interessenten/*")
				.hasAnyRole("ADMIN", "BERATER")
				.requestMatchers("/api/admin/me", "/api/admin/me/passwort").hasAnyRole("ADMIN", "BERATER")
				.requestMatchers("/api/admin/**", "/api/dashboard/**").hasRole("ADMIN")
				.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(csrfRequestHandler)
						// Script clients (curl etc.) send this header instead of a CSRF token. Browsers cannot add
						// custom headers cross-site without a CORS preflight, which this app does not allow.
						.ignoringRequestMatchers(request -> request.getHeader("X-API-Client") != null
								&& request.getHeader("Authorization") != null));
		return http.build();
	}

	@Bean
	UserDetailsService userDetailsService(@Value("${app.admin.username}") final String username,
			@Value("${app.admin.password}") final String password,
			final PasswordEncoder encoder, final ExperteRepository experten, final ExpertenRollenService rollen) {
		return new ExpertenUserDetailsService(
				User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build(), experten,
				rollen);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
