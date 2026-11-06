package de.kiexperten.ki;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Proxy zur lokalen KI, damit der API-Key nie im Browser landet. */
@RestController
public class KiChatController {

	private static final int MAX_PER_MINUTE = 10;
	private static final Logger log = LoggerFactory.getLogger(KiChatController.class);
	private static final String SYSTEM_PROMPT = "Du bist der Sprachassistent von KI-Experten, freiberuflichen "
			+ "KI-Experten für KMU (KI in House). Antworte auf Deutsch, freundlich und sehr kurz (max. 3 Sätze), "
			+ "ohne Markdown, Listen oder Sonderzeichen, da die Antwort vorgelesen wird.";

	private final ObjectMapper mapper = new ObjectMapper();
	private final Map<String, long[]> hits = new ConcurrentHashMap<>();
	private final HttpClient client;
	private final String baseUrl;
	private final String completionsPath;
	private final String apiKey;
	private final String model;
	private final ChatProtokollService protokolle;

	public KiChatController(
			final ChatProtokollService protokolle,
			@Value("${app.ki.base-url:https://100.65.185.69/v1}") final String baseUrl,
			@Value("${app.ki.completions-path:/chat/completions}") final String completionsPath,
			@Value("${app.ki.api-key:}") final String apiKey,
			@Value("${app.ki.model:swift-1.5-qwen3.8-27b}") final String model,
			@Value("${app.ki.trust-all-tls:false}") final boolean trustAll) throws Exception {
		this.baseUrl = baseUrl;
		this.completionsPath = completionsPath;
		this.apiKey = apiKey;
		this.model = model;
		this.protokolle = protokolle;
		final HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
		if (trustAll) {
			// nur für den internen Server mit selbstsigniertem Zertifikat
			final SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(null, new TrustManager[] { new X509TrustManager() {
				public void checkClientTrusted(final X509Certificate[] c, final String a) {
				}

				public void checkServerTrusted(final X509Certificate[] c, final String a) {
				}

				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			} }, null);
			final var params = new javax.net.ssl.SSLParameters();
			params.setEndpointIdentificationAlgorithm("");
			builder.sslContext(ctx).sslParameters(params);
		}
		this.client = builder.build();
	}

	public record ChatMessage(@NotBlank @Size(max = 20) String role, @NotBlank @Size(max = 2000) String content) {
	}

	public record ChatRequest(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ChatMessage> messages, UUID gespraechId) {
	}

	public record ChatResponse(String reply) {
	}

	@PostMapping("/api/ki/chat")
	public ChatResponse chat(@RequestBody @Valid final ChatRequest request, final HttpServletRequest http)
			throws Exception {
		final var body = createBody(request, http, false);
		final ChatProtokoll protokoll = protokolle.beginne(request);
		try {
			final HttpResponse<String> res = client.send(upstreamRequest(body), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			final JsonNode content = mapper.readTree(res.body()).at("/choices/0/message/content");
			if (content.isMissingNode() || content.isNull()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			final String reply = content.asText().replaceAll("(?s)<think>.*?</think>", "").trim();
			protokolle.abschliessen(protokoll, reply, "ABGESCHLOSSEN");
			return new ChatResponse(reply);
		} catch (IOException | InterruptedException | RuntimeException e) {
			protokolliereFehler(protokoll, null, e);
			throw e;
		}
	}

	@PostMapping(value = "/api/ki/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<StreamingResponseBody> stream(@RequestBody @Valid final ChatRequest request,
			final HttpServletRequest http) throws Exception {
		final var body = createBody(request, http, true);
		final ChatProtokoll protokoll = protokolle.beginne(request);
		final HttpResponse<InputStream> response;
		try {
			response = client.send(upstreamRequest(body), HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				response.body().close();
				log.warn("KI-Stream antwortete mit HTTP {}", response.statusCode());
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			protokolliereFehler(protokoll, null, e);
			throw e;
		}
		final StreamingResponseBody stream = output -> forwardStream(response.body(), output, protokoll);
		return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform")
				.header("X-Accel-Buffering", "no").header("X-Chat-Id", protokoll.getGespraechId())
				.contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
	}

	private void protokolliereFehler(final ChatProtokoll protokoll, final String antwort, final Exception ursache) {
		try {
			protokolle.abschliessen(protokoll, antwort, "FEHLER");
		} catch (RuntimeException fehler) {
			log.error("Chatprotokoll konnte nicht aktualisiert werden", fehler);
			ursache.addSuppressed(fehler);
		}
	}

	private com.fasterxml.jackson.databind.node.ObjectNode createBody(final ChatRequest request,
			final HttpServletRequest http, final boolean stream) {
		if (apiKey.isBlank())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		limit(http.getRemoteAddr());
		if (request.messages() == null || request.messages().isEmpty())
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		final var messages = mapper.createArrayNode();
		messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
		for (final ChatMessage m : request.messages()) {
			if (!"user".equals(m.role()) && !"assistant".equals(m.role()))
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			messages.addObject().put("role", m.role()).put("content", m.content());
		}
		final var body = mapper.createObjectNode();
		body.put("model", model);
		body.set("messages", messages);
		body.put("stream", stream);
		body.put("max_tokens", 1500);
		body.putObject("chat_template_kwargs").put("enable_thinking", true);
		return body;
	}

	private HttpRequest upstreamRequest(final JsonNode body) throws IOException {
		return HttpRequest.newBuilder(URI.create(baseUrl + completionsPath))
				.timeout(Duration.ofSeconds(120))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
	}

	private void sendEvent(final OutputStream output, final String event, final String text) throws IOException {
		final String data = mapper.writeValueAsString(Map.of("text", text));
		output.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
		output.flush();
	}

	private void forwardStream(final InputStream input, final OutputStream output, final ChatProtokoll protokoll) {
		final ThinkFilter filter = new ThinkFilter();
		final StringBuilder antwort = new StringBuilder();
		boolean done = false;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			final StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					if (data.isEmpty()) continue;
					final String payload = data.toString();
					data.setLength(0);
					if ("[DONE]".equals(payload)) {
						done = true;
						break;
					}
					final JsonNode chunk = mapper.readTree(payload);
					if (chunk.hasNonNull("error")) throw new IOException("KI-Stream meldet einen Fehler");
					final JsonNode content = chunk.at("/choices/0/delta/content");
					if (content.isTextual()) {
						final String visible = filter.accept(content.asText());
						if (!visible.isEmpty()) {
							sendEvent(output, "delta", visible);
							antwort.append(visible);
						}
					}
				} else if (line.startsWith("data:")) {
					if (!data.isEmpty()) data.append('\n');
					data.append(line.substring(5).stripLeading());
				}
			}
			if (!done) throw new IOException("KI-Stream wurde vorzeitig beendet");
			final String rest = filter.finish();
			if (!rest.isEmpty()) {
				sendEvent(output, "delta", rest);
				antwort.append(rest);
			}
			protokolle.abschliessen(protokoll, antwort.toString(), "ABGESCHLOSSEN");
			sendEvent(output, "done", "");
		} catch (IOException | RuntimeException e) {
			log.warn("KI-Stream fehlgeschlagen", e);
			protokolliereFehler(protokoll, antwort.toString(), e);
			try {
				sendEvent(output, "error", "Die KI-Antwort wurde unterbrochen.");
			} catch (IOException writeError) {
				log.debug("KI-Stream konnte nicht an den Client gesendet werden", writeError);
			}
		}
	}

	private static final class ThinkFilter {
		private final StringBuilder pending = new StringBuilder();
		private boolean thinking;

		String accept(final String text) {
			pending.append(text);
			final StringBuilder visible = new StringBuilder();
			while (!pending.isEmpty()) {
				final String tag = thinking ? "</think>" : "<think>";
				final int index = pending.indexOf(tag);
				if (index >= 0) {
					if (!thinking) visible.append(pending, 0, index);
					pending.delete(0, index + tag.length());
					thinking = !thinking;
					continue;
				}
				int keep = Math.min(pending.length(), tag.length() - 1);
				while (keep > 0 && !tag.startsWith(pending.substring(pending.length() - keep))) keep--;
				final int count = pending.length() - keep;
				if (!thinking) visible.append(pending, 0, count);
				pending.delete(0, count);
				break;
			}
			return visible.toString();
		}

		String finish() throws IOException {
			if (thinking) throw new IOException("Unvollständiger KI-Denkblock");
			return pending.toString();
		}
	}

	private void limit(final String ip) {
		final long now = System.currentTimeMillis();
		final long[] h = hits.compute(ip, (k, v) -> {
			if (v == null || now - v[0] > 60_000)
				return new long[] { now, 1 };
			v[1]++;
			return v;
		});
		if (h[1] > MAX_PER_MINUTE)
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
	}
}
