package de.kiexperten.requirementsmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class RequirementsMcpApplication {
	public static void main(final String[] args) {
		SpringApplication.run(RequirementsMcpApplication.class, args);
	}
}