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
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import de.kiexperten.Zeit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

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
				if (request.contains("upstream-fehler")) {
					exchange.sendResponseHeaders(503, -1);
					exchange.close();
					return;
				}
				if (request.contains("\"stream\":false")) {
					final byte[] body = "{\"choices\":[{\"message\":{\"content\":\"<think>geheim</think>Antwort.\"}}]}"
							.getBytes(StandardCharsets.UTF_8);
					exchange.sendResponseHeaders(200, body.length);
					try (var out = exchange.getResponseBody()) { out.write(body); }
					return;
				}
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

	@Autowired
	ChatProtokollRepository protokolle;

	@Autowired
	ChatProtokollService protokollService;

	private HttpResponse<java.io.InputStream> request(final String question) throws Exception {
		return request(question, null);
	}

	private HttpResponse<java.io.InputStream> request(final String question, final UUID gespraechId) throws Exception {
		final String auth = Base64.getEncoder().encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ki/chat/stream"))
				.header("Authorization", "Basic " + auth)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"" + question
						+ "\"}]" + (gespraechId == null ? "" : ",\"gespraechId\":\"" + gespraechId + "\"") + "}"))
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
			final String id = response.headers().firstValue("X-Chat-Id").orElseThrow();
			final var entry = protokolle.findAll().stream().filter(p -> p.getGespraechId().equals(id)).findFirst().orElseThrow();
			assertEquals("test", entry.getNachricht());
			assertEquals("Erster Satz. Zweiter Satz.", entry.getAntwort());
			assertEquals("ABGESCHLOSSEN", entry.getStatus());
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
		final String id = response.headers().firstValue("X-Chat-Id").orElseThrow();
		final var entry = protokolle.findAll().stream().filter(p -> p.getGespraechId().equals(id)).findFirst().orElseThrow();
		assertEquals("FEHLER", entry.getStatus());
		assertEquals("Erster Satz. Zweiter Satz.", entry.getAntwort());
	}

	@Test
	void leavesExistingChatsAccessibleOnlyToAdmins() throws Exception {
		continueStream = new CountDownLatch(0);
		final UUID id = UUID.randomUUID();
		final ChatProtokoll previous = new ChatProtokoll();
		previous.setGespraechId(id.toString());
		previous.setZeitpunkt(Zeit.nowUtc());
		previous.setRolle("user");
		previous.setNachricht("vorhandener Chat");
		previous.setAntwort("vorhandene Antwort");
		previous.setStatus("ABGESCHLOSSEN");
		protokolle.saveAndFlush(previous);
		for (final String question : new String[] { "erste", "zweite" }) {
			final var response = request(question, id);
			response.body().readAllBytes();
			assertEquals(id.toString(), response.headers().firstValue("X-Chat-Id").orElseThrow());
		}
		assertEquals(3, protokolle.findAll().stream().filter(p -> p.getGespraechId().equals(id.toString())).count());
		final URI uri = URI.create("http://127.0.0.1:" + port + "/api/admin/chats");
		final HttpClient client = HttpClient.newHttpClient();
		assertEquals(401, client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
		final String auth = Base64.getEncoder().encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var admin = client.send(HttpRequest.newBuilder(uri).header("Authorization", "Basic " + auth).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, admin.statusCode());
		assertTrue(admin.body().contains(id.toString()));
		assertTrue(admin.body().contains("vorhandener Chat"));
	}

	@Test
	void legacyEndpointRecordsChat() throws Exception {
		final String auth = Base64.getEncoder().encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + port + "/api/ki/chat"))
				.header("Authorization", "Basic " + auth).header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"alt\"},"
						+ "{\"role\":\"assistant\",\"content\":\"alte Antwort\"},{\"role\":\"user\",\"content\":\"legacy\"}]}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertEquals("{\"reply\":\"Antwort.\"}", response.body());
		final var entry = protokolle.findAll().stream().filter(p -> p.getNachricht().equals("legacy")).findFirst().orElseThrow();
		assertEquals("Antwort.", entry.getAntwort());
		assertEquals("ABGESCHLOSSEN", entry.getStatus());
	}

	@Test
	void upstreamFailureIsRecorded() throws Exception {
		final var response = request("upstream-fehler");
		response.body().close();
		assertEquals(502, response.statusCode());
		final var entry = protokolle.findAll().stream().filter(p -> p.getNachricht().equals("upstream-fehler"))
				.findFirst().orElseThrow();
		assertEquals("FEHLER", entry.getStatus());
	}

	@Test
	@Transactional
	void removesEntriesAtThirtyDayBoundaryOnly() {
		final LocalDateTime now = Zeit.nowUtc();
		final LocalDateTime cutoff = now.minusDays(30);
		final ChatProtokoll older = entryAt(cutoff.minusSeconds(1));
		final ChatProtokoll boundary = entryAt(cutoff);
		final ChatProtokoll newer = entryAt(cutoff.plusSeconds(1));
		protokolle.saveAndFlush(older);
		protokolle.saveAndFlush(boundary);
		protokolle.saveAndFlush(newer);
		protokollService.loescheBis(cutoff);
		protokolle.flush();
		assertFalse(protokolle.existsById(older.getId()));
		assertFalse(protokolle.existsById(boundary.getId()));
		assertTrue(protokolle.existsById(newer.getId()));
	}

	@Test
	@Transactional
	void scheduledCleanupUsesThirtyDayRetention() {
		final LocalDateTime now = Zeit.nowUtc();
		final ChatProtokoll expired = protokolle.saveAndFlush(entryAt(now.minusDays(31)));
		final ChatProtokoll retained = protokolle.saveAndFlush(entryAt(now.minusDays(29)));
		protokollService.loescheAbgelaufene();
		assertFalse(protokolle.existsById(expired.getId()));
		assertTrue(protokolle.existsById(retained.getId()));
	}

	private ChatProtokoll entryAt(final LocalDateTime time) {
		final var entry = new ChatProtokoll();
		entry.setZeitpunkt(time);
		entry.setGespraechId(UUID.randomUUID().toString());
		entry.setRolle("user");
		entry.setNachricht("Test");
		entry.setStatus("ABGESCHLOSSEN");
		return entry;
	}
}
