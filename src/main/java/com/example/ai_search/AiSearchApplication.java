package com.example.ai_search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@EnableCaching
@SpringBootApplication
public class AiSearchApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiSearchApplication.class, args);
	}

}
