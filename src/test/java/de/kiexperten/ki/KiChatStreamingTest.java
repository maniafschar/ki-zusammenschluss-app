package de.kiexperten.ki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:ki-stream-test",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"app.admin.username=admin", "app.admin.password=local-test-password",
		"app.ki.api-key=local-test-key"
})
class KiChatStreamingTest {
	private static final HttpServer upstream;
	private static volatile CountDownLatch continueStream = new CountDownLatch(1);

	static {
		try {
			upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			upstream.createContext("/chat/completions", exchange -> {
				final String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
				exchange.sendResponseHeaders(200, 0);
				try (var out = exchange.getResponseBody()) {
					out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}\r\n\r\n"
							+ "data: {\"choices\":[{\"delta\":{\"content\":\"nk>geheim</thi\"}}]}\r\n\r\n"
							+ "data: {\"choices\":[{\"delta\":{\"content\":\"nk>Erster Satz. \"}}]}\r\n\r\n")
							.getBytes(StandardCharsets.UTF_8));
					out.flush();
					if (!continueStream.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
					out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"Zweiter Satz.\"}}]}\n\n"
							+ (request.contains("abbruch") ? "" : "data: [DONE]\n\n")).getBytes(StandardCharsets.UTF_8));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
			});
			upstream.start();
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@DynamicPropertySource
	static void properties(final DynamicPropertyRegistry registry) {
		registry.add("app.ki.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
	}

	@AfterAll
	static void stopServer() {
		upstream.stop(0);
	}

	@LocalServerPort
	int port;

	private HttpResponse<java.io.InputStream> request(final String question) throws Exception {
		final String auth = Base64.getEncoder().encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ki/chat/stream"))
				.header("Authorization", "Basic " + auth)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"" + question + "\"}]}"))
				.build(), HttpResponse.BodyHandlers.ofInputStream());
	}

	@Test
	void streamsBeforeCompletionAndHidesSplitThinkTags() throws Exception {
		continueStream = new CountDownLatch(1);
		try {
			final var response = request("test");
			assertEquals(200, response.statusCode());
			try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				assertEquals("event: delta", reader.readLine());
				final String first = reader.readLine();
				assertTrue(first.contains("Erster Satz."));
				assertFalse(first.contains("geheim"));
				assertEquals("", reader.readLine());
				continueStream.countDown();
				final String rest = reader.lines().reduce("", (a, b) -> a + b + "\n");
				assertTrue(rest.contains("Zweiter Satz."));
				assertTrue(rest.contains("event: done"));
			}
		} finally {
			continueStream.countDown();
		}
	}

	@Test
	void reportsTruncatedUpstream() throws Exception {
		continueStream = new CountDownLatch(0);
		final var response = request("abbruch");
		final String stream = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(stream.contains("event: error"));
		assertFalse(stream.contains("event: done"));
	}
}
