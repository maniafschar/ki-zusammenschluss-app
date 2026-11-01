package de.kiexperten.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the one script configured via app.admin.script-path. The path is never
 * taken from the request, so the client cannot choose what gets executed.
 */
@RestController
public class ScriptController {

	private static final int MAX_OUTPUT = 20_000;

	private final String scriptPath;
	private final long timeoutSeconds;

	public ScriptController(@Value("${app.admin.script-path:}") final String scriptPath,
			@Value("${app.admin.script-timeout-seconds:60}") final long timeoutSeconds) {
		this.scriptPath = scriptPath;
		this.timeoutSeconds = timeoutSeconds;
	}

	@PostMapping("/api/admin/script/run")
	public ResponseEntity<Map<String, Object>> run() throws IOException, InterruptedException {
		if (scriptPath.isBlank()) {
			return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
					.body(Map.of("output", "Kein Skript konfiguriert (app.admin.script-path)."));
		}
		final Path script = Path.of(scriptPath).toAbsolutePath().normalize();
		if (!Files.isRegularFile(script) || !Files.isExecutable(script)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("output", "Skript nicht gefunden oder nicht ausführbar."));
		}
		// Output goes to a temp file so the timeout also applies to scripts that hang.
		final Path log = Files.createTempFile("admin-script", ".log");
		try {
			final Process process = new ProcessBuilder(script.toString())
					.directory(script.getParent().toFile())
					.redirectErrorStream(true)
					.redirectOutput(log.toFile())
					.start();
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
						.body(Map.of("output", "Zeitüberschreitung nach " + timeoutSeconds + " s."));
			}
			final byte[] all = Files.readAllBytes(log);
			final String output = new String(all, 0, Math.min(all.length, MAX_OUTPUT), StandardCharsets.UTF_8);
			return ResponseEntity.ok(Map.of("exitCode", process.exitValue(), "output", output));
		} finally {
			Files.deleteIfExists(log);
		}
	}
}
