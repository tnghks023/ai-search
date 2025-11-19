package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import com.google.genai.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplLlmFallbackTest {

    @Test
    @DisplayName("Gemini가 계속 실패하면 fallback 문구를 반환한다")
    void callLLM_returnsFallback_whenGeminiAlwaysFails() throws Exception {
        // given
        // 1) WebClient는 이 테스트에서 사용 안 하므로 그냥 mock만 생성
        WebClient braveWebClient = Mockito.mock(WebClient.class);

        // 2) Gemini Client + Models mock 준비
        Client geminiClient = Mockito.mock(Client.class);
        Models models = Mockito.mock(Models.class);


        // Client 내부의 models 필드에 우리가 만든 mock 주입
        ReflectionTestUtils.setField(geminiClient, "models", models);

        // 3) SearchServiceImpl 인스턴스 생성 (Lombok @RequiredArgsConstructor 기반)
        SearchServiceImpl searchService = new SearchServiceImpl(braveWebClient, geminiClient);

        // @Value 주입되는 llmModel만 테스트에서 직접 세팅
        ReflectionTestUtils.setField(searchService, "llmModel", "test-model");

        // 4) models.generateContent(...)가 항상 예외를 던지도록 설정
        when(models.generateContent(anyString(), anyString(), isNull()))
                .thenThrow(new RuntimeException("Gemini error"));

        // LLM에 넘길 가짜 출처/콘텐츠
        List<SourceDto> sources = List.of(
                new SourceDto(1, "테스트 제목", "https://example.com", "테스트 스니펫")
        );
        List<String> contents = List.of("본문 내용 일부");

        // private 메서드 callLLM(String, List<SourceDto>, List<String>)을 reflection으로 호출
        Method callLlmMethod = SearchServiceImpl.class
                .getDeclaredMethod("callLLM", String.class, List.class, List.class);
        callLlmMethod.setAccessible(true);

        // when
        String answer = (String) callLlmMethod.invoke(
                searchService,
                "테스트 질문입니다.",
                sources,
                contents
        );

        // then
        assertThat(answer)
                .isNotNull()
                .contains("죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.")
                .contains("잠시 후 다시 시도해 주세요.")
                .contains("아래 출처들을 직접 참고해 주세요.");
    }
}
