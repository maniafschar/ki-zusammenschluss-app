package de.kiexperten.ki;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ChatProtokollController {
	private final ChatProtokollRepository repository;

	public ChatProtokollController(final ChatProtokollRepository repository) {
		this.repository = repository;
	}

	record ChatSeite(List<ChatProtokoll> content, int number, int totalPages, long totalElements,
			boolean first, boolean last) {
	}

	@GetMapping("/api/admin/chats")
	ChatSeite chats(@RequestParam(defaultValue = "0") final int seite) {
		if (seite < 0 || seite > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		final var page = repository.findAll(PageRequest.of(seite, 50, Sort.by(Sort.Direction.DESC, "zeitpunkt", "id")));
		return new ChatSeite(page.getContent(), page.getNumber(), page.getTotalPages(), page.getTotalElements(),
				page.isFirst(), page.isLast());
	}
}
