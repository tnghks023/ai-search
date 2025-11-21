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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplLlmFallbackTest {

    @Test
    @DisplayName("Gemini가 계속 실패하면 fallback 문구를 반환한다")
    void callLLM_returnsFallback_whenGeminiAlwaysFails() throws Exception {
        // given

        // Gemini Client + Models mock 준비
        Client geminiClient = Mockito.mock(Client.class);
        Models models = Mockito.mock(Models.class);

        // Client 내부의 models 필드에 우리가 만든 mock 주입
        ReflectionTestUtils.setField(geminiClient, "models", models);

        GeminiAnswerGenerator answerGenerator = new GeminiAnswerGenerator(geminiClient);

        // @Value 주입되는 llmModel만 테스트에서 직접 세팅
        ReflectionTestUtils.setField(answerGenerator, "llmModel", "test-model");
        ReflectionTestUtils.setField(answerGenerator, "llmTimeoutSeconds", 12L);

        // 4) models.generateContent(...)가 항상 예외를 던지도록 설정
        when(models.generateContent(anyString(), anyString(), isNull()))
                .thenThrow(new RuntimeException("Gemini error"));

        // LLM에 넘길 가짜 출처/콘텐츠
        List<SourceDto> sources = List.of(
                new SourceDto(1, "테스트 제목", "https://example.com", "테스트 스니펫")
        );
        List<String> contents = List.of("본문 내용 일부");


        String answer = answerGenerator.generateAnswer(
                "테스트 질문입니다.",
                sources,
                contents);

        // then
        assertThat(answer)
                .isNotNull()
                .contains("죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.")
                .contains("잠시 후 다시 시도해 주세요.")
                .contains("아래 출처들을 직접 참고해 주세요.");
    }

    @Test
    @DisplayName("Gemini 예외 발생 시 최대 2회까지 재시도(backoff) 후 fallback 문구를 반환한다")
    void callLLM_retriesTwiceAndThenFallback_whenGeminiAlwaysThrows() throws Exception {
        // given
        WebClient braveWebClient = mock(WebClient.class);

        Client geminiClient = mock(Client.class);
        Models models = mock(Models.class);
        ReflectionTestUtils.setField(geminiClient, "models", models);

        GeminiAnswerGenerator answerGenerator = new GeminiAnswerGenerator(geminiClient);

        ReflectionTestUtils.setField(answerGenerator, "llmModel", "test-model");
        ReflectionTestUtils.setField(answerGenerator, "llmTimeoutSeconds", 12L);

        // generateContent 호출 횟수 카운트
        AtomicInteger callCount = new AtomicInteger(0);

        when(models.generateContent(anyString(), anyString(), isNull()))
                .thenAnswer(invocation -> {
                    callCount.incrementAndGet();
                    throw new RuntimeException("Gemini error");
                });

        List<SourceDto> sources = List.of(
                new SourceDto(1, "테스트 제목", "https://example.com", "테스트 스니펫")
        );
        List<String> contents = List.of("본문 내용 일부");

        long start = System.currentTimeMillis();

        String answer = answerGenerator.generateAnswer(                "테스트 질문입니다.",
                sources,
                contents);

        long elapsed = System.currentTimeMillis() - start;

        // then
        // 1) 최종적으로 fallback 문구를 반환해야 함
        assertThat(answer)
                .isNotNull()
                .contains("죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.");

        // 2) generateContent()는 최대 시도 횟수(2회)만큼 호출되어야 함
        assertThat(callCount.get())
                .as("Gemini generateContent 호출 횟수")
                .isEqualTo(2);

        // 3) 논리 타임아웃(4초) + backoff(0.3s → 0.6s)를 감안해도,
        //    예외를 즉시 던지는 mock이므로 수 초씩 기다리지는 않아야 함 (sanity check 용)
        assertThat(elapsed)
                .as("전체 LLM 호출 시간")
                .isLessThan(Duration.ofSeconds(5).toMillis());
    }
}
