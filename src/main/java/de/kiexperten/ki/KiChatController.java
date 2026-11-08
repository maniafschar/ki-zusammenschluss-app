package de.kiexperten.ki;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

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
	private static final String LEGACY_SYSTEM_PROMPT = "Du bist der deutschsprachige Assistent des freiberuflichen KI-Experten-Teams für KMU. "
			+ "Antworte freundlich und knapp (höchstens 3 Sätze). Führe das Gespräch als Multiple-Choice-Dialog: "
			+ "Stelle pro Antwort genau eine Folgefrage und biete dafür genau 3 nummerierte Auswahlmöglichkeiten an. Freitext ist erlaubt. "
			+ "Kläre diese vier Punkte: Branche, hauptsächlich genutzte Software, gewünschte KI-Unterstützung und Dringlichkeit beziehungsweise Zeitrahmen. "
			+ "Frage nur nach noch offenen Punkten und behaupte nicht, der Dialog sei abgeschlossen, bevor alle vier geklärt sind. "
			+ "Lenke fachfremde Fragen höflich zum aktuellen Punkt zurück. Wenn alle Punkte geklärt sind und Interesse besteht, "
			+ "lade zu einem Termin am Seitenende ein.";

	private static final String REQUIREMENTS_SYSTEM_PROMPT = "Du bist der deutschsprachige Assistent für die KI-Reifegrad-Erhebung eines Unternehmens. "
			+ "Nutze bei jedem Gesprächsschritt die MCP-Werkzeuge und rufe zuerst get_next_requirement_step mit der Gesprächs-ID auf. "
			+ "Es gibt genau sechs Pflichtschritte. Beginne jede Frage mit dem unveränderten Titel im Format 'Schritt {stepNumber}: {title}'. Verwende Frage und Antwortoptionen aus dem aktuellen MCP-Schritt unverändert und bleibe in der vorgegebenen Reihenfolge. "
			+ "Bei selectionMode=single stelle eine Frage mit nummerierten Optionen. Bei selectionMode=multiple zeige den Hinweis 'Mehrfachauswahl möglich (max. N Antworten)' mit dem maxSelections-Wert aus dem MCP-Schritt; der Nutzer kann bis zu diesem Maximum auswählen. "
			+ "Bei selectionMode=text stelle die gelieferte Frage und erzeuge ein Freitextfeld mit der Zeile 'Freitextfeld: ' gefolgt vom inputPlaceholder. "
			+ "Wenn bereits eine Frage gestellt wurde, bewerte die letzte Nutzeraussage anhand der Akzeptanzkriterien. Rufe record_requirement_answer auf und übergib die Antwort als answers-Array. Bei Auswahloptionen übergib die exakten Optionstexte einzeln; bei Freitext ein Element. "
			+ "Du entscheidest selbst, ob die Antwort genügt. Bei unklaren Antworten stelle eine konkrete Rückfrage und schließe den Schritt nicht ab. Der Server prüft Auswahlmaximum und Reihenfolge. "
			+ "Erkläre die Optionstexte nicht um und erfinde keine Antwortoptionen. Nach dem sechsten Schritt schreibe eine knappe, sachliche Zusammenfassung der Antworten in Stichpunkten ohne Anrede und Grußformel, die der Nutzer als Nachricht an das Team übernehmen kann; verweise danach kurz auf den Terminkalender am Seitenende. "
			+ "Beantworte keine sachfremden Fragen; lenke freundlich zum aktuellen Schritt zurück.";
	private static final String WELCOME_INTRO = "Schön, dass Sie da sind! Ich bin der KI-Assistent des KI Experten Teams: "
			+ "freiberufliche Spezialisten, unabhängig und mit einer klaren Haltung. KI soll in Ihrem Unternehmen echten Nutzen bringen, "
			+ "nicht nur gut klingen.\n\n"
			+ "Dafür brauche ich Ihr ehrliches Bild. Es gibt hier keine falschen Antworten: Wer noch ganz am Anfang steht, "
			+ "bekommt von uns genauso eine passende Einschätzung wie jemand, der schon mittendrin ist. "
			+ "Je ehrlicher Ihre Antworten, desto weniger Zeit verlieren wir beide mit Dingen, die nicht zu Ihnen passen.\n\n"
			+ "Der Einstieg ist eine kurze Frage. Wählen Sie einfach aus, was am ehesten zutrifft, oder antworten Sie in eigenen Worten.";
	private static final String WELCOME_FALLBACK_QUESTION = "Wie würden Sie den Einsatz von KI in Ihrem Unternehmen heute beschreiben?\n\n"
			+ "- Noch gar nicht: Wir haben bisher keine KI im Einsatz.\n"
			+ "- Vereinzelt: Wir probieren KI-Tools aus.\n"
			+ "- Gezielt: KI wird in einzelnen Prozessen genutzt.\n"
			+ "- Strategisch: KI ist fester Bestandteil unserer Planung.";
	private static final int MAX_TOOL_ROUNDS = 5;
	private static final int MAX_TOOL_CALLS = 10;
	private final ObjectMapper mapper = new ObjectMapper();
	private final Map<String, long[]> hits = new ConcurrentHashMap<>();
	private final HttpClient client;
	private final String baseUrl;
	private final String completionsPath;
	private final String transcriptionsPath;
	private final String speechPath;
	private final String apiKey;
	private final String model;
	private final String transcriptionModel;
	private final String speechModel;
	private final String speechVoice;
	private final String speechFormat;
	private final ChatProtokollService protokolle;
	private final RequirementsMcpGateway requirementsMcp;

	public KiChatController(
			final ChatProtokollService protokolle,
			final ObjectProvider<RequirementsMcpGateway> requirementsMcp,
			@Value("${app.ki.base-url:https://100.65.185.69/v1}") final String baseUrl,
			@Value("${app.ki.completions-path:/chat/completions}") final String completionsPath,
			@Value("${app.ki.transcriptions-path:/audio/transcriptions}") final String transcriptionsPath,
			@Value("${app.ki.speech-path:/audio/speech}") final String speechPath,
			@Value("${app.ki.api-key:}") final String apiKey,
			@Value("${app.ki.model:swift-1.5-qwen3.8-27b}") final String model,
			@Value("${app.ki.transcription-model:ohr-datei}") final String transcriptionModel,
			@Value("${app.ki.speech-model:stimme}") final String speechModel,
			@Value("${app.ki.speech-voice:1930}") final String speechVoice,
			@Value("${app.ki.speech-format:wav}") final String speechFormat,
			@Value("${app.ki.trust-all-tls:false}") final boolean trustAll) throws Exception {
		this.baseUrl = baseUrl;
		this.completionsPath = completionsPath;
		this.transcriptionsPath = transcriptionsPath;
		this.speechPath = speechPath;
		this.apiKey = apiKey;
		this.model = model;
		this.transcriptionModel = transcriptionModel;
		this.speechModel = speechModel;
		this.speechVoice = speechVoice;
		this.speechFormat = speechFormat;
		this.protokolle = protokolle;
		this.requirementsMcp = requirementsMcp.getIfAvailable();
		final HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
		if (trustAll) {
			// nur für den internen Server mit selbstsigniertem Zertifikat
			final SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(null, new TrustManager[] { new X509TrustManager() {
				@Override
				public void checkClientTrusted(final X509Certificate[] c, final String a) {
				}

				@Override
				public void checkServerTrusted(final X509Certificate[] c, final String a) {
				}

				@Override
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

	public record ChatRequest(@NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ChatMessage> messages,
			UUID gespraechId) {
	}

	public record ChatResponse(String reply) {
	}

	private record KiAntwort(String text, boolean abgeschlossen) {
	}

	public record TranscriptionResponse(String text) {
	}

	public record SpeechRequest(@NotBlank @Size(max = 6000) String input) {
	}

	@PostMapping(value = "/api/ki/audio/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TranscriptionResponse transcribe(@RequestPart("audio") final MultipartFile audio,
			final HttpServletRequest http) throws IOException, InterruptedException {
		if (this.apiKey.isBlank())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		this.limit(http.getRemoteAddr());
		if (audio.isEmpty() || audio.getSize() > 20L * 1024 * 1024)
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		final String boundary = "----KiAudio" + UUID.randomUUID().toString().replace("-", "");
		final byte[] payload = this.transcriptionPayload(audio, boundary);
		final HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + this.transcriptionsPath))
				.timeout(Duration.ofSeconds(120))
				.header("Authorization", "Bearer " + this.apiKey)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
		final HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			log.warn("KI-Transkription antwortete mit HTTP {}", response.statusCode());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
		final JsonNode text = this.mapper.readTree(response.body()).get("text");
		if (text == null || !text.isTextual() || text.asText().isBlank())
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		return new TranscriptionResponse(text.asText().trim());
	}

	@PostMapping(value = "/api/ki/audio/speech", produces = { "audio/mpeg", "audio/wav", "audio/ogg" })
	public ResponseEntity<byte[]> synthesize(@RequestBody @Valid final SpeechRequest request,
			final HttpServletRequest http) throws IOException, InterruptedException {
		if (this.apiKey.isBlank())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		this.limit(http.getRemoteAddr());
		final var body = this.mapper.createObjectNode();
		body.put("model", this.speechModel);
		body.put("input", request.input());
		body.put("voice", this.speechVoice);
		body.put("response_format", this.speechFormat);
		final HttpRequest upstream = HttpRequest.newBuilder(URI.create(this.baseUrl + this.speechPath))
				.timeout(Duration.ofSeconds(120))
				.header("Authorization", "Bearer " + this.apiKey)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(body))).build();
		final HttpResponse<byte[]> response = this.client.send(upstream, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200 || response.body().length == 0) {
			log.warn("KI-Sprachausgabe antwortete mit HTTP {}", response.statusCode());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
		return ResponseEntity.ok().contentType(this.speechMediaType()).header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(response.body());
	}

	private byte[] transcriptionPayload(final MultipartFile audio, final String boundary) throws IOException {
		final ByteArrayOutputStream payload = new ByteArrayOutputStream();
		final String filename = audio.getOriginalFilename() == null ? "recording.webm"
				: audio.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_");
		final String contentType = audio.getContentType() == null ? "application/octet-stream" : audio.getContentType();
		this.writePart(payload, boundary, "file", filename, contentType, audio.getBytes());
		this.writePart(payload, boundary, "model", null, null,
				this.transcriptionModel.getBytes(StandardCharsets.UTF_8));
		this.writePart(payload, boundary, "language", null, null, "de".getBytes(StandardCharsets.UTF_8));
		this.writePart(payload, boundary, "response_format", null, null, "json".getBytes(StandardCharsets.UTF_8));
		payload.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return payload.toByteArray();
	}

	private void writePart(final ByteArrayOutputStream payload, final String boundary, final String name,
			final String filename, final String contentType, final byte[] value) throws IOException {
		payload.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"")
				.getBytes(StandardCharsets.UTF_8));
		if (filename != null)
			payload.write(("; filename=\"" + filename + "\"\r\nContent-Type: " + contentType)
					.getBytes(StandardCharsets.UTF_8));
		payload.write("\r\n\r\n".getBytes(StandardCharsets.UTF_8));
		payload.write(value);
		payload.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	private MediaType speechMediaType() {
		return switch (this.speechFormat.toLowerCase()) {
			case "wav" -> MediaType.parseMediaType("audio/wav");
			case "opus", "ogg" -> MediaType.parseMediaType("audio/ogg");
			default -> MediaType.parseMediaType("audio/mpeg");
		};
	}

	public record WelcomeResponse(String gespraechId, String text) {
	}

	@GetMapping("/api/ki/welcome")
	public WelcomeResponse welcome(final HttpServletRequest http) {
		this.limit(http.getRemoteAddr());
		final String gespraechId = UUID.randomUUID().toString();
		return new WelcomeResponse(gespraechId, WELCOME_INTRO + "\n\n" + this.ersteFrage(gespraechId));
	}

	private String ersteFrage(final String gespraechId) {
		if (this.requirementsMcp != null) {
			try {
				final var arguments = this.mapper.createObjectNode().put("conversationId", gespraechId);
				final JsonNode result = this.mapper
						.readTree(this.requirementsMcp.call("get_next_requirement_step", arguments));
				final JsonNode step = result.path("step");
				if (!step.path("question").isTextual())
					throw new IllegalStateException("Anforderungsschritt ohne Frage");
				return this.schrittAlsMarkdown(result, step);
			} catch (final IOException | RuntimeException e) {
				log.warn("Erster Anforderungsschritt konnte nicht geladen werden", e);
			}
		}
		return WELCOME_FALLBACK_QUESTION;
	}

	private String schrittAlsMarkdown(final JsonNode result, final JsonNode step) {
		final StringBuilder markdown = new StringBuilder();
		markdown.append("**Schritt ").append(result.path("stepNumber").asInt(1)).append(" von ")
				.append(result.path("totalSteps").asInt(1)).append(":** ").append(step.path("question").asText());
		if ("text".equals(step.path("selectionMode").asText())) {
			markdown.append("\n\nFreitextfeld: ").append(step.path("inputPlaceholder").asText());
			return markdown.toString();
		}
		markdown.append("\n");
		for (final JsonNode option : step.path("options"))
			markdown.append("\n- ").append(option.asText());
		if ("multiple".equals(step.path("selectionMode").asText()))
			markdown.append("\n\nMehrfachauswahl möglich (max. ").append(step.path("maxSelections").asInt(1))
					.append(" Antworten).");
		return markdown.toString();
	}

	@PostMapping("/api/ki/chat")
	public ChatResponse chat(@RequestBody @Valid final ChatRequest request, final HttpServletRequest http)
			throws Exception {
		final ChatProtokoll protokoll = this.protokolle.beginne(request);
		try {
			final var body = this.createBody(request, http, false);
			final String reply = this.completeWithTools(body, protokoll.getGespraechId()).text();
			this.protokolle.abschliessen(protokoll, reply, "ABGESCHLOSSEN");
			return new ChatResponse(reply);
		} catch (IOException | InterruptedException | RuntimeException e) {
			this.protokolliereFehler(protokoll, null, e);
			throw e;
		}
	}

	@PostMapping(value = "/api/ki/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<StreamingResponseBody> stream(@RequestBody @Valid final ChatRequest request,
			final HttpServletRequest http) throws Exception {
		final ChatProtokoll protokoll = this.protokolle.beginne(request);
		if (this.requirementsMcp != null) {
			final var toolBody = this.createBody(request, http, false);
			final StreamingResponseBody toolStream = output -> {
				try {
					final KiAntwort antwort = this.completeWithTools(toolBody, protokoll.getGespraechId());
					this.protokolle.abschliessen(protokoll, antwort.text(), "ABGESCHLOSSEN");
					this.sendEvent(output, "delta", antwort.text());
					this.sendEvent(output, "done", Map.of("text", "", "abgeschlossen", antwort.abgeschlossen()));
				} catch (final IOException | InterruptedException | RuntimeException e) {
					if (e instanceof InterruptedException)
						Thread.currentThread().interrupt();
					this.protokolliereFehler(protokoll, null, e);
					this.sendEvent(output, "error", "Die KI-Antwort konnte nicht vollständig geladen werden.");
				} catch (final Exception e) {
					this.protokolliereFehler(protokoll, null, e);
					this.sendEvent(output, "error", "Die KI-Antwort konnte nicht vollständig geladen werden.");
				}
			};
			return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform")
					.header("X-Accel-Buffering", "no").header("X-Chat-Id", protokoll.getGespraechId())
					.contentType(MediaType.TEXT_EVENT_STREAM).body(toolStream);
		}
		final var body = this.createBody(request, http, true);
		final HttpResponse<InputStream> response;
		try {
			response = this.client.send(this.upstreamRequest(body), HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				response.body().close();
				log.warn("KI-Stream antwortete mit HTTP {}", response.statusCode());
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			}
		} catch (IOException | InterruptedException | RuntimeException e) {
			this.protokolliereFehler(protokoll, null, e);
			throw e;
		}
		final StreamingResponseBody stream = output -> this.forwardStream(response.body(), output, protokoll);
		return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform")
				.header("X-Accel-Buffering", "no").header("X-Chat-Id", protokoll.getGespraechId())
				.contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
	}

	private void protokolliereFehler(final ChatProtokoll protokoll, final String antwort, final Exception ursache) {
		try {
			this.protokolle.abschliessen(protokoll, antwort, "FEHLER");
		} catch (final RuntimeException fehler) {
			log.error("Chatprotokoll konnte nicht aktualisiert werden", fehler);
			ursache.addSuppressed(fehler);
		}
	}

	private com.fasterxml.jackson.databind.node.ObjectNode createBody(final ChatRequest request,
			final HttpServletRequest http, final boolean stream) {
		if (this.apiKey.isBlank())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		this.limit(http.getRemoteAddr());
		if (request.messages() == null || request.messages().isEmpty())
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "system").put("content",
				this.requirementsMcp == null ? LEGACY_SYSTEM_PROMPT : REQUIREMENTS_SYSTEM_PROMPT);
		for (final ChatMessage m : request.messages()) {
			if (!"user".equals(m.role()) && !"assistant".equals(m.role()))
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
			messages.addObject().put("role", m.role()).put("content", m.content());
		}
		final var body = this.mapper.createObjectNode();
		body.put("model", this.model);
		body.set("messages", messages);
		body.put("stream", stream);
		body.put("max_tokens", 1500);
		body.putObject("chat_template_kwargs").put("enable_thinking", true);
		if (this.requirementsMcp != null) {
			body.set("tools", this.requirementsMcp.toolDefinitions());
			body.put("tool_choice", "auto");
		}
		return body;
	}

	private KiAntwort completeWithTools(final com.fasterxml.jackson.databind.node.ObjectNode body,
			final String conversationId) throws Exception {
		boolean abgeschlossen = false;
		if (this.requirementsMcp != null) {
			final com.fasterxml.jackson.databind.node.ObjectNode system = (com.fasterxml.jackson.databind.node.ObjectNode) body
					.path("messages").get(0);
			system.put("content",
					system.path("content").asText() + " Gesprächs-ID für MCP-Werkzeuge: " + conversationId);
		}
		int executedCalls = 0;
		for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
			final HttpResponse<String> response = this.client.send(this.upstreamRequest(body),
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			final JsonNode message = this.mapper.readTree(response.body()).at("/choices/0/message");
			if (message.isMissingNode() || message.isNull())
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			final JsonNode calls = message.path("tool_calls");
			if (this.requirementsMcp == null || !calls.isArray() || calls.isEmpty()) {
				final JsonNode content = message.path("content");
				if (content.isMissingNode() || content.isNull())
					throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
				return new KiAntwort(content.asText().replaceAll("(?s)<think>.*?</think>", "").trim(), abgeschlossen);
			}
			if (executedCalls + calls.size() > MAX_TOOL_CALLS)
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
			final ArrayNode messages = (ArrayNode) body.path("messages");
			messages.add(message.deepCopy());
			for (final JsonNode call : calls) {
				final String toolCallId = call.path("id").asText();
				final JsonNode function = call.path("function");
				final String toolName = function.path("name").asText();
				final JsonNode rawArguments = function.path("arguments");
				final JsonNode arguments = rawArguments.isTextual() ? this.mapper.readTree(rawArguments.asText())
						: rawArguments;
				if (!arguments.isObject() || toolCallId.isBlank() || toolName.isBlank())
					throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
				final String result = this.requirementsMcp.call(toolName, arguments);
				abgeschlossen |= meldetAbschluss(result);
				messages.addObject().put("role", "tool").put("tool_call_id", toolCallId).put("content", result);
				executedCalls++;
			}
		}
		throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

	private boolean meldetAbschluss(final String toolResult) {
		try {
			return this.mapper.readTree(toolResult).path("completed").asBoolean(false);
		} catch (final IOException | RuntimeException e) {
			return false;
		}
	}

	private HttpRequest upstreamRequest(final JsonNode body) throws IOException {
		return HttpRequest.newBuilder(URI.create(this.baseUrl + this.completionsPath))
				.timeout(Duration.ofSeconds(120))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + this.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(body))).build();
	}

	private void sendEvent(final OutputStream output, final String event, final String text) throws IOException {
		this.sendEvent(output, event, Map.<String, Object>of("text", text));
	}

	private void sendEvent(final OutputStream output, final String event, final Map<String, Object> payload)
			throws IOException {
		final String data = this.mapper.writeValueAsString(payload);
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
					if (data.isEmpty())
						continue;
					final String payload = data.toString();
					data.setLength(0);
					if ("[DONE]".equals(payload)) {
						done = true;
						break;
					}
					final JsonNode chunk = this.mapper.readTree(payload);
					if (chunk.hasNonNull("error"))
						throw new IOException("KI-Stream meldet einen Fehler");
					final JsonNode content = chunk.at("/choices/0/delta/content");
					if (content.isTextual()) {
						final String visible = filter.accept(content.asText());
						if (!visible.isEmpty()) {
							this.sendEvent(output, "delta", visible);
							antwort.append(visible);
						}
					}
				} else if (line.startsWith("data:")) {
					if (!data.isEmpty())
						data.append('\n');
					data.append(line.substring(5).stripLeading());
				}
			}
			if (!done)
				throw new IOException("KI-Stream wurde vorzeitig beendet");
			final String rest = filter.finish();
			if (!rest.isEmpty()) {
				this.sendEvent(output, "delta", rest);
				antwort.append(rest);
			}
			this.protokolle.abschliessen(protokoll, antwort.toString(), "ABGESCHLOSSEN");
			this.sendEvent(output, "done", "");
		} catch (IOException | RuntimeException e) {
			log.warn("KI-Stream fehlgeschlagen", e);
			this.protokolliereFehler(protokoll, antwort.toString(), e);
			try {
				this.sendEvent(output, "error", "Die KI-Antwort wurde unterbrochen.");
			} catch (final IOException writeError) {
				log.debug("KI-Stream konnte nicht an den Client gesendet werden", writeError);
			}
		}
	}

	private static final class ThinkFilter {
		private final StringBuilder pending = new StringBuilder();
		private boolean thinking;

		String accept(final String text) {
			this.pending.append(text);
			final StringBuilder visible = new StringBuilder();
			while (!this.pending.isEmpty()) {
				final String tag = this.thinking ? "</think>" : "<think>";
				final int index = this.pending.indexOf(tag);
				if (index >= 0) {
					if (!this.thinking)
						visible.append(this.pending, 0, index);
					this.pending.delete(0, index + tag.length());
					this.thinking = !this.thinking;
					continue;
				}
				int keep = Math.min(this.pending.length(), tag.length() - 1);
				while (keep > 0 && !tag.startsWith(this.pending.substring(this.pending.length() - keep)))
					keep--;
				final int count = this.pending.length() - keep;
				if (!this.thinking)
					visible.append(this.pending, 0, count);
				this.pending.delete(0, count);
				break;
			}
			return visible.toString();
		}

		String finish() throws IOException {
			if (this.thinking)
				throw new IOException("Unvollständiger KI-Denkblock");
			return this.pending.toString();
		}
	}

	private void limit(final String ip) {
		final long now = System.currentTimeMillis();
		final long[] h = this.hits.compute(ip, (k, v) -> {
			if (v == null || now - v[0] > 60_000)
				return new long[] { now, 1 };
			v[1]++;
			return v;
		});
		if (h[1] > MAX_PER_MINUTE)
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
	}
}
