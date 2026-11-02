package de.kiexperten.content;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

@Service
public class ExpertenRollenService {
	private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

	private final ExpertenRollenRepository rollen;

	public ExpertenRollenService(final ExpertenRollenRepository rollen) {
		this.rollen = rollen;
	}

	public static LocalDate heute() {
		return LocalDate.now(ZONE);
	}

	/** Role in effect today; experts without any entry count as advisors. */
	public ExpertenRolle aktuelle(final Long experteId) {
		return this.rollen.findFirstByExperteIdAndSeitLessThanEqualOrderBySeitDescIdDesc(experteId, heute())
				.map(ExpertenRollenEintrag::getRolle).orElse(ExpertenRolle.BERATER);
	}
}
