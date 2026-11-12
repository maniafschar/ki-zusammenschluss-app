package de.kiexperten.ki;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatProtokollRepository extends JpaRepository<ChatProtokoll, Long> {
	List<ChatProtokoll> findByGespraechIdOrderByIdAsc(String gespraechId);

	@Modifying
	@Query("delete from ChatProtokoll c where c.zeitpunkt <= :grenze")
	int deleteExpired(@Param("grenze") LocalDateTime grenze);
}
