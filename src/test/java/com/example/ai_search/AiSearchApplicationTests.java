package com.example.ai_search;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "llm.api.key=dummy-llm-key",
        "llm.model=gemini-test-model",
        "search.api.key=dummy-search-key",
        "spring.profiles.active=dev",
        "spring.cache.type=none"
})
class AiSearchApplicationTests {

	@Test
	void contextLoads() {
	}

}
