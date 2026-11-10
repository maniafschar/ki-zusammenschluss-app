package de.kiexperten.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AsyncWebConfig implements WebMvcConfigurer {

	// Streamed chat turns with tool calls take longer than Spring's default async timeout of 30 s.
	private static final long STREAM_TIMEOUT_MS = 300_000L;

	@Override
	public void configureAsyncSupport(final AsyncSupportConfigurer configurer) {
		configurer.setDefaultTimeout(STREAM_TIMEOUT_MS);
	}
}
