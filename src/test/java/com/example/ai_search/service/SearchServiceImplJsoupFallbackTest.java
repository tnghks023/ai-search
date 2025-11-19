package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceImplJsoupFallbackTest {

    @Test
    @DisplayName("Jsoup 본문 크롤링 중 예외가 발생하면 해당 URL은 빈 문자열로 fallback 된다")
    void fetchPageTextsParallel_usesEmptyStringOnJsoupFailure() throws Exception {

        // 1) SearchServiceImpl 생성
        WebClient braveWebClient = Mockito.mock(WebClient.class);
        Client geminiClient = Mockito.mock(Client.class);

        SearchServiceImpl searchService = new SearchServiceImpl(braveWebClient, geminiClient);

        // 2) 테스트용 SourceDto 리스트 (하나만 사용, 실패 케이스)
        SourceDto badSource = new SourceDto(1, "BAD", "https://bad.example.com", "bad snippet");
        List<SourceDto> sources = List.of(badSource);

        // 3) Jsoup static 메서드 mock
        try (MockedStatic<Jsoup> jsoupMock = Mockito.mockStatic(Jsoup.class)) {
            // 어떤 URL이 들어와도 Jsoup.connect(...)는 예외를 던지게 함
            jsoupMock.when(() -> Jsoup.connect(Mockito.anyString()))
                    .thenThrow(new RuntimeException("Jsoup error"));

            // 4) private 메서드 fetchPageTextsParallel(List<SourceDto>) 리플렉션으로 호출
            Method method = SearchServiceImpl.class
                    .getDeclaredMethod("fetchPageTextsParallel", List.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<String> contents = (List<String>) method.invoke(searchService, sources);

            // 5) 검증: 리스트 크기는 1, 그 값은 "" 여야 함
            assertThat(contents).hasSize(1);
            assertThat(contents.get(0)).isEqualTo("");  // Jsoup 실패 → fallback ""
        }
    }
}
