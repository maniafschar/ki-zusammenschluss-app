package de.kiexperten.ki;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Proxy zur lokalen KI, damit der API-Key nie im Browser landet. */
@RestController
public class KiChatController {

	private static final int MAX_PER_MINUTE = 10;
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

	public KiChatController(
			@Value("${app.ki.base-url:https://100.65.185.69/v1}") final String baseUrl,
			@Value("${app.ki.completions-path:/chat/completions}") final String completionsPath,
			@Value("${app.ki.api-key:}") final String apiKey,
			@Value("${app.ki.model:swift-1.5-qwen3.8-27b}") final String model,
			@Value("${app.ki.trust-all-tls:false}") final boolean trustAll) throws Exception {
		this.baseUrl = baseUrl;
		this.completionsPath = completionsPath;
		this.apiKey = apiKey;
		this.model = model;
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

	public record ChatRequest(@Size(min = 1, max = 20) List<@Valid ChatMessage> messages) {
	}

	public record ChatResponse(String reply) {
	}

	@PostMapping("/api/ki/chat")
	public ChatResponse chat(@RequestBody @Valid final ChatRequest request, final HttpServletRequest http)
			throws Exception {
		if (apiKey.isBlank())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		limit(http.getRemoteAddr());
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
		body.put("stream", false);
		body.put("max_tokens", 1500);
		body.putObject("chat_template_kwargs").put("enable_thinking", true);
		final HttpResponse<String> res = client.send(HttpRequest.newBuilder(URI.create(baseUrl + completionsPath))
				.timeout(Duration.ofSeconds(120))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
				HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() != 200)
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		final JsonNode content = mapper.readTree(res.body()).at("/choices/0/message/content");
		if (content.isMissingNode() || content.isNull())
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		// Denkblöcke entfernen, falls der Server sie im Content mitliefert
		return new ChatResponse(content.asText().replaceAll("(?s)<think>.*?</think>", "").trim());
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
