package com.example.g_one_legacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import io.github.cdimascio.dotenv.Dotenv;

@SpringBootApplication
@EnableAsync
public class GOneLegacyApplication {

	public static void main(String[] args) {
		// Load .env variables into System properties
		Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
		dotenv.entries().forEach(entry -> {
			System.setProperty(entry.getKey(), entry.getValue());
		});

		SpringApplication.run(GOneLegacyApplication.class, args);
	}

}
