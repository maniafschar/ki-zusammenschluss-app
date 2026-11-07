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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.sun.net.httpserver.HttpServer;

import de.kiexperten.Zeit;

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
					try (var out = exchange.getResponseBody()) {
						out.write(body);
					}
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
					if (!continueStream.await(5, TimeUnit.SECONDS))
						throw new IllegalStateException("Test timeout");
					out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"Zweiter Satz.\"}}]}\n\n"
							+ (request.contains("abbruch") ? "" : "data: [DONE]\n\n"))
							.getBytes(StandardCharsets.UTF_8));
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
			});
			upstream.createContext("/audio/transcriptions", exchange -> {
				final String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				if (!request.contains("name=\"file\"") || !request.contains("sample-audio")) {
					exchange.sendResponseHeaders(400, -1);
					exchange.close();
					return;
				}
				final byte[] body = "{\"text\":\"Erkannter Satz\"}".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				try (var out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			upstream.createContext("/audio/speech", exchange -> {
				exchange.getRequestBody().readAllBytes();
				final byte[] body = { 'I', 'D', '3', 1, 2, 3 };
				exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
				exchange.sendResponseHeaders(200, body.length);
				try (var out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			upstream.start();
		} catch (final Exception e) {
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
		return this.request(question, null);
	}

	private HttpResponse<java.io.InputStream> request(final String question, final UUID gespraechId) throws Exception {
		final String auth = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat/stream"))
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
			final var response = this.request("test");
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
			final var entry = this.protokolle.findAll().stream().filter(p -> p.getGespraechId().equals(id)).findFirst()
					.orElseThrow();
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
		final var response = this.request("abbruch");
		final String stream = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(stream.contains("event: error"));
		assertFalse(stream.contains("event: done"));
		final String id = response.headers().firstValue("X-Chat-Id").orElseThrow();
		final var entry = this.protokolle.findAll().stream().filter(p -> p.getGespraechId().equals(id)).findFirst()
				.orElseThrow();
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
		this.protokolle.saveAndFlush(previous);
		for (final String question : new String[] { "erste", "zweite" }) {
			final var response = this.request(question, id);
			response.body().readAllBytes();
			assertEquals(id.toString(), response.headers().firstValue("X-Chat-Id").orElseThrow());
		}
		assertEquals(3,
				this.protokolle.findAll().stream().filter(p -> p.getGespraechId().equals(id.toString())).count());
		final URI uri = URI.create("http://127.0.0.1:" + this.port + "/api/admin/chats");
		final HttpClient client = HttpClient.newHttpClient();
		assertEquals(401, client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
				.statusCode());
		final String auth = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var admin = client.send(
				HttpRequest.newBuilder(uri).header("Authorization", "Basic " + auth).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, admin.statusCode());
		assertTrue(admin.body().contains(id.toString()));
		assertTrue(admin.body().contains("vorhandener Chat"));
	}

	@Test
	void legacyEndpointRecordsChat() throws Exception {
		final String auth = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat"))
				.header("Authorization", "Basic " + auth).header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"alt\"},"
						+ "{\"role\":\"assistant\",\"content\":\"alte Antwort\"},{\"role\":\"user\",\"content\":\"legacy\"}]}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertEquals("{\"reply\":\"Antwort.\"}", response.body());
		final var entry = this.protokolle.findAll().stream().filter(p -> p.getNachricht().equals("legacy")).findFirst()
				.orElseThrow();
		assertEquals("Antwort.", entry.getAntwort());
		assertEquals("ABGESCHLOSSEN", entry.getStatus());
	}

	@Test
	void proxiesTranscriptionAndSpeechAudio() throws Exception {
		final String auth = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final String boundary = "----voice-test-boundary";
		final String multipart = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"audio\"; filename=\"sample.webm\"\r\n"
				+ "Content-Type: audio/webm\r\n\r\nsample-audio\r\n--" + boundary + "--\r\n";
		final var transcription = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + this.port + "/api/ki/audio/transcriptions"))
				.header("Authorization", "Basic " + auth).header("X-API-Client", "test")
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, transcription.statusCode());
		assertEquals("{\"text\":\"Erkannter Satz\"}", transcription.body());

		final var speech = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + this.port + "/api/ki/audio/speech"))
				.header("Authorization", "Basic " + auth).header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"Hallo\"}")).build(),
				HttpResponse.BodyHandlers.ofByteArray());
		assertEquals(200, speech.statusCode());
		assertEquals("audio/mpeg", speech.headers().firstValue("Content-Type").orElseThrow());
		assertEquals("ID3", new String(speech.body(), 0, 3, StandardCharsets.UTF_8));
	}

	@Test
	void upstreamFailureIsRecorded() throws Exception {
		final var response = this.request("upstream-fehler");
		response.body().close();
		assertEquals(502, response.statusCode());
		final var entry = this.protokolle.findAll().stream().filter(p -> p.getNachricht().equals("upstream-fehler"))
				.findFirst().orElseThrow();
		assertEquals("FEHLER", entry.getStatus());
	}

	@Test
	@Transactional
	void removesEntriesAtThirtyDayBoundaryOnly() {
		final LocalDateTime now = Zeit.nowUtc();
		final LocalDateTime cutoff = now.minusDays(7);
		final ChatProtokoll older = this.entryAt(cutoff.minusSeconds(1));
		final ChatProtokoll boundary = this.entryAt(cutoff);
		final ChatProtokoll newer = this.entryAt(cutoff.plusSeconds(1));
		this.protokolle.saveAndFlush(older);
		this.protokolle.saveAndFlush(boundary);
		this.protokolle.saveAndFlush(newer);
		this.protokollService.loescheBis(cutoff);
		this.protokolle.flush();
		assertFalse(this.protokolle.existsById(older.getId()));
		assertFalse(this.protokolle.existsById(boundary.getId()));
		assertTrue(this.protokolle.existsById(newer.getId()));
	}

	@Test
	@Transactional
	void scheduledCleanupUsesThirtyDayRetention() {
		final LocalDateTime now = Zeit.nowUtc();
		final ChatProtokoll expired = this.protokolle.saveAndFlush(this.entryAt(now.minusDays(8)));
		final ChatProtokoll retained = this.protokolle.saveAndFlush(this.entryAt(now.minusDays(6)));
		this.protokollService.loescheAbgelaufene();
		assertFalse(this.protokolle.existsById(expired.getId()));
		assertTrue(this.protokolle.existsById(retained.getId()));
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
