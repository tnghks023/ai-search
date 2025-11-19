package com.example.ai_search;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "SEARCH_API_KEY=dummy",
        "LLM_API_KEY=dummy",
        "SPRING_PROFILES_ACTIVE=dev"
})
class AiSearchApplicationTests {

	@Test
	void contextLoads() {
	}

}
