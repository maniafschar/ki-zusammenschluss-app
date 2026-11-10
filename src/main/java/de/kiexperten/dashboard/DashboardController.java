package de.kiexperten.dashboard;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.kiexperten.audit.CreateGuard;
import de.kiexperten.content.ExperteRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dashboard API; protected by the admin login like every /api/admin style
 * endpoint.
 */
@RestController
public class DashboardController {

	private final DashboardRepository repository;
	private final ExperteRepository experten;
	private final ObjectMapper mapper;
	private final CreateGuard guard;

	public DashboardController(final DashboardRepository repository, final ExperteRepository experten,
			final ObjectMapper mapper, final CreateGuard guard) {
		this.guard = guard;
		this.repository = repository;
		this.experten = experten;
		this.mapper = mapper;
	}

	record ExpertRef(Long id, String name) {
	}

	record Wissen(BigInteger id, String title, String content, boolean kiVerfuegbar, java.sql.Timestamp modifiedAt,
			List<DashboardRepository.AnhangInfo> anhaenge) {
	}

	record Snapshot(List<Wissen> wissen, List<Todo> todos, String info, List<ExpertRef> experten) {
	}

	record WissenRequest(@NotBlank @Size(max = 200) String title, @Size(max = 2_000_000) String content,
			boolean kiVerfuegbar) {
	}

	private static final Safelist HTML = Safelist.relaxed().removeTags("img")
			.addTags("s", "u", "div", "span").addEnforcedAttribute("a", "rel", "noopener noreferrer")
			.addEnforcedAttribute("a", "target", "_blank").removeProtocols("a", "href", "ftp");

	record TodoRequest(@NotBlank @Size(max = 200) String note, Long expertId, LocalDate dueDate) {
	}

	/**
	 * assignment is only applied when the client sends "expertId" or "dueDate" (see
	 * TodoUpdate#changesAssignment).
	 */
	record TodoUpdate(Boolean completed, Long expertId, LocalDate dueDate, Boolean assign) {
		boolean changesAssignment() {
			return Boolean.TRUE.equals(this.assign);
		}
	}

	record InfoRequest(@Size(max = 1_000_000) String note) {
	}

	/**
	 * Everything is returned encrypted for the RSA public key the browser generated
	 * for this request.
	 */
	@GetMapping("/api/dashboard")
	HybridEncryption.Encrypted load(@RequestHeader("publicKey") final String publicKey) {
		final Map<BigInteger, List<DashboardRepository.AnhangInfo>> files = this.repository.anhaenge().stream()
				.collect(Collectors.groupingBy(DashboardRepository.AnhangInfo::wissensId));
		final List<Wissen> wissen = this.repository.wissen().stream().map(w -> toWissen(w, files.get(w.getId())))
				.toList();
		final Snapshot snapshot = new Snapshot(wissen, this.repository.todos(),
				this.repository.info(), this.experten.findAllByOrderByPositionAscIdAsc().stream()
						.map(e -> new ExpertRef(e.getId(), e.getName())).toList());
		try {
			return HybridEncryption.encrypt(publicKey, this.mapper.writeValueAsBytes(snapshot));
		} catch (final GeneralSecurityException | IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger öffentlicher Schlüssel");
		} catch (final JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Wissen toWissen(final Wissensquelle w, final List<DashboardRepository.AnhangInfo> files) {
		return new Wissen(w.getId(), w.getTitle(), w.getContent(), w.isKiVerfuegbar(), w.getModifiedAt(),
				files == null ? List.of() : files);
	}

	/**
	 * Rich text comes from a contenteditable; whatever the client sends is reduced
	 * to harmless markup.
	 */
	private static String clean(final String html) {
		return html == null ? "" : Jsoup.clean(html, "", HTML, new Document.OutputSettings().prettyPrint(false));
	}

	@PostMapping("/api/dashboard/wissen")
	Wissen addWissen(@Valid @RequestBody final WissenRequest request, final Principal principal) {
		this.guard.require(principal);
		return toWissen(
				this.repository.addWissen(request.title().trim(), clean(request.content()), request.kiVerfuegbar()),
				null);
	}

	@PutMapping("/api/dashboard/wissen/{id}")
	Wissen updateWissen(@PathVariable final BigInteger id, @Valid @RequestBody final WissenRequest request) {
		final Wissensquelle w = this.repository.updateWissen(id, request.title().trim(), clean(request.content()),
				request.kiVerfuegbar());
		if (w == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return toWissen(w, this.repository.anhaenge().stream().filter(a -> id.equals(a.wissensId())).toList());
	}

	@DeleteMapping("/api/dashboard/wissen/{id}")
	ResponseEntity<Void> deleteWissen(@PathVariable final BigInteger id) {
		return this.repository.deleteWissen(id) ? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

	@PostMapping("/api/dashboard/wissen/{id}/anhang")
	List<DashboardRepository.AnhangInfo> upload(@PathVariable final BigInteger id,
			@RequestParam("dateien") final List<MultipartFile> dateien) throws IOException {
		if (!this.repository.wissenExists(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		final List<DashboardRepository.AnhangInfo> saved = new ArrayList<>();
		for (final MultipartFile file : dateien) {
			if (file.isEmpty()) {
				continue;
			}
			final String name = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "datei"));
			final String base = name.substring(name.lastIndexOf('/') + 1);
			saved.add(this.repository.addAnhang(id, base.isBlank() ? "datei" : StringUtils.truncate(base, 255),
					StringUtils.truncate(file.getContentType(), 150), file.getBytes()));
		}
		return saved;
	}

	@GetMapping("/api/dashboard/wissen/{id}/anhang/{anhangId}")
	ResponseEntity<byte[]> download(@PathVariable final BigInteger id, @PathVariable final BigInteger anhangId) {
		final Anhang a = this.repository.anhang(id, anhangId);
		if (a == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(a.getName(), StandardCharsets.UTF_8).build()
								.toString())
				.header("X-Content-Type-Options", "nosniff")
				.contentType(MediaType.APPLICATION_OCTET_STREAM).body(a.getData());
	}

	@DeleteMapping("/api/dashboard/wissen/{id}/anhang/{anhangId}")
	ResponseEntity<Void> deleteAnhang(@PathVariable final BigInteger id, @PathVariable final BigInteger anhangId) {
		return this.repository.deleteAnhang(id, anhangId) ? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

	@PostMapping("/api/dashboard/todo")
	Todo addTodo(@Valid @RequestBody final TodoRequest request, final Principal principal) {
		this.guard.require(principal);
		return this.repository.addTodo(request.note().trim(), this.checkedExpert(request.expertId()), request.dueDate());
	}

	@PutMapping("/api/dashboard/todo/{id}")
	Todo updateTodo(@PathVariable final BigInteger id, @RequestBody final TodoUpdate update) {
		final Todo todo = this.repository.updateTodo(id, update.completed(), update.changesAssignment(),
				update.changesAssignment() ? this.checkedExpert(update.expertId()) : null, update.dueDate());
		if (todo == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return todo;
	}

	@DeleteMapping("/api/dashboard/todo/{id}")
	ResponseEntity<Void> deleteTodo(@PathVariable final BigInteger id) {
		return this.repository.deleteTodo(id) ? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

	@PutMapping("/api/dashboard/info")
	ResponseEntity<Void> saveInfo(@Valid @RequestBody final InfoRequest request) {
		this.repository.saveInfo(request.note() == null ? "" : request.note());
		return ResponseEntity.noContent().build();
	}

	private Long checkedExpert(final Long expertId) {
		if (expertId != null && !this.experten.existsById(expertId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannter Experte");
		}
		return expertId;
	}
}
