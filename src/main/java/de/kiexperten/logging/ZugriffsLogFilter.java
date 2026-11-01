package de.kiexperten.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Logs every request, including those rejected by Spring Security. Runs before the security chain
 * (order -100), so the user name is read from the Basic auth header; the password is never stored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ZugriffsLogFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(ZugriffsLogFilter.class);
	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

	private final ZugriffsLogRepository repository;

	public ZugriffsLogFilter(final ZugriffsLogRepository repository) {
		this.repository = repository;
	}

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain chain) throws ServletException, IOException {
		final long start = System.nanoTime();
		final ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
		Throwable failure = null;
		try {
			chain.doFilter(request, wrapped);
		} catch (final ServletException | IOException | RuntimeException | Error e) {
			failure = e;
			throw e;
		} finally {
			// Exceptions handled inside Spring MVC only show up as a request attribute.
			if (failure == null && request.getAttribute(DispatcherServlet.class.getName() + ".EXCEPTION") instanceof final Throwable t) {
				failure = t;
			}
			final long bytes = wrapped.getContentSize();
			wrapped.copyBodyToResponse();
			write(request, wrapped, bytes, (System.nanoTime() - start) / 1_000_000, failure);
		}
	}

	private void write(final HttpServletRequest req, final HttpServletResponse res, final long bytes,
			final long durationMs, final Throwable failure) {
		try {
			final ZugriffsLog log = new ZugriffsLog();
			log.setZeitpunkt(LocalDateTime.now(ZONE));
			log.setMethode(cut(req.getMethod(), 10));
			log.setPfad(cut(req.getRequestURI(), 500));
			log.setQuery(cut(maskSecrets(req.getQueryString()), 1000));
			log.setStatus(res.getStatus());
			log.setDauerMs(durationMs);
			log.setIp(cut(req.getRemoteAddr(), 64));
			log.setWeitergeleitetVon(cut(req.getHeader("X-Forwarded-For"), 300));
			log.setBenutzer(cut(basicUser(req.getHeader("Authorization")), 120));
			log.setUserAgent(cut(req.getHeader("User-Agent"), 500));
			log.setReferer(cut(maskSecrets(req.getHeader("Referer")), 500));
			log.setSprache(cut(req.getHeader("Accept-Language"), 100));
			log.setContentType(cut(res.getContentType(), 100));
			log.setAntwortBytes(bytes);
			log.setFehler(failure == null ? null : cut(stackTrace(failure), 4000));
			this.repository.save(log);
		} catch (final RuntimeException e) {
			// Logging must never break the actual request.
			LOG.warn("Zugriff konnte nicht protokolliert werden: {}", e.toString());
		}
	}

	private static String stackTrace(final Throwable t) {
		final StringWriter out = new StringWriter();
		t.printStackTrace(new PrintWriter(out));
		return out.toString();
	}

	private static String basicUser(final String header) {
		if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
			return null;
		}
		try {
			final String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
					StandardCharsets.UTF_8);
			final int colon = decoded.indexOf(':');
			return colon < 0 ? decoded : decoded.substring(0, colon);
		} catch (final IllegalArgumentException e) {
			return null;
		}
	}

	// Confirmation tokens are credentials and must not end up in the log.
	private static String maskSecrets(final String value) {
		return value == null ? null : value.replaceAll("(?i)(token=)[^&#]*", "$1***");
	}

	private static String cut(final String value, final int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
