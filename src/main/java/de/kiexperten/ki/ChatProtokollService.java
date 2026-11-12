package de.kiexperten.ki;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.kiexperten.Zeit;

@Service
public class ChatProtokollService {
	private static final Logger log = LoggerFactory.getLogger(ChatProtokollService.class);
	private final ChatProtokollRepository repository;

	public ChatProtokollService(final ChatProtokollRepository repository) {
		this.repository = repository;
	}

	void speichereBegruessung(final String gespraechId, final String text) {
		final var protokoll = new ChatProtokoll();
		protokoll.setGespraechId(gespraechId);
		protokoll.setZeitpunkt(Zeit.nowUtc());
		protokoll.setRolle("assistant");
		protokoll.setNachricht(text);
		protokoll.setStatus("ABGESCHLOSSEN");
		this.repository.saveAndFlush(protokoll);
	}

	List<KiChatController.ChatMessage> verlauf(final String gespraechId) {
		final List<KiChatController.ChatMessage> verlauf = new ArrayList<>();
		for (final ChatProtokoll zeile : this.repository.findByGespraechIdOrderByIdAsc(gespraechId)) {
			if (!"ABGESCHLOSSEN".equals(zeile.getStatus()))
				continue;
			if ("assistant".equals(zeile.getRolle())) {
				verlauf.add(new KiChatController.ChatMessage("assistant", zeile.getNachricht()));
				continue;
			}
			verlauf.add(new KiChatController.ChatMessage("user", zeile.getNachricht()));
			if (zeile.getAntwort() != null && !zeile.getAntwort().isBlank())
				verlauf.add(new KiChatController.ChatMessage("assistant", zeile.getAntwort()));
		}
		return verlauf;
	}

	ChatProtokoll beginne(final KiChatController.ChatRequest request) {
		final var message = request.messages().get(request.messages().size() - 1);
		final var protokoll = new ChatProtokoll();
		protokoll
				.setGespraechId((request.gespraechId() == null ? UUID.randomUUID() : request.gespraechId()).toString());
		protokoll.setZeitpunkt(Zeit.nowUtc());
		protokoll.setRolle(message.role());
		protokoll.setNachricht(message.content());
		protokoll.setStatus("LAEUFT");
		return this.repository.saveAndFlush(protokoll);
	}

	void abschliessen(final ChatProtokoll protokoll, final String antwort, final String status) {
		protokoll.setAntwort(antwort);
		protokoll.setStatus(status);
		this.repository.saveAndFlush(protokoll);
	}

	@Scheduled(fixedDelay = 3_600_000)
	@Transactional
	public void loescheAbgelaufene() {
		final int count = this.loescheBis(Zeit.nowUtc().minusDays(7));
		if (count > 0)
			log.info("{} abgelaufene Chatprotokolle gelöscht", count);
	}

	int loescheBis(final LocalDateTime grenze) {
		return this.repository.deleteExpired(grenze);
	}
}
