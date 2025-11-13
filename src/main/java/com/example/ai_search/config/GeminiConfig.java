package com.example.ai_search.config;

import com.google.genai.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeminiConfig {

    @Value("${llm.api.key}")
    private String llmApiKey;

    @Bean
    public Client geminiClient() {
        // API 키를 명시적으로 넣어서 Developer API 사용
        return Client.builder()
                .apiKey(llmApiKey)
                .build();
    }
}
