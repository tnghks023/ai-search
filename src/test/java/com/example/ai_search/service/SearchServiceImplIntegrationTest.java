package com.example.ai_search.service;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SearchServiceImpl 통합 테스트:
 * - QueryNormalizer를 통해 쿼리가 정규화되는지
 * - 정규화된 쿼리 기준으로 캐시가 동작해서
 *   SourceRepository / ContentFetcher / AnswerGenerator가
 *   한 번만 호출되는지 검증
 */
@SpringBootTest(properties = {
        "SEARCH_API_KEY=dummy",          // @Value placeholder 방지용
        "LLM_API_KEY=dummy",             // @Value placeholder 방지용
        "spring.profiles.active=dev"     // dev 프로파일로 띄우고 싶을 때
})
// 캐시 상태가 다른 테스트에 영향을 주지 않게 하고 싶다면 필요 시 사용
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SearchServiceImplIntegrationTest {

    @MockitoBean
    SourceRepository sourceRepository;

    @MockitoBean
    ContentFetcher contentFetcher;

    @MockitoBean
    AnswerGenerator answerGenerator;

    @Autowired
    SearchServiceImpl searchService;

    // QueryNormalizer 는 실제 빈(@Component) 사용

    @Test
    @DisplayName("정규화된 쿼리 기준으로 캐시가 동작하여, 같은 의미의 쿼리는 한 번만 파이프라인을 탄다")
    void search_normalization_and_cache_work_together() {
        // given
        // 정규화 결과: "spring boot" 라고 가정
        String normalizedQuery = "spring boot";

        List<SourceDto> sources = List.of(
                new SourceDto(1, "Spring Boot Guide", "https://example.com", "스프링 부트 소개")
        );
        List<String> contents = List.of("본문 내용 일부");
        String llmAnswer = "이것은 스프링 부트에 대한 요약 답변입니다.";

        // Mock 동작 정의
        when(sourceRepository.getSources(normalizedQuery)).thenReturn(sources);
        when(contentFetcher.fetchContents(sources)).thenReturn(contents);
        when(answerGenerator.generateAnswer(normalizedQuery, sources, contents))
                .thenReturn(llmAnswer);

        // when
        SearchResponseDto resp1 = searchService.search("   Spring   Boot  ");
        SearchResponseDto resp2 = searchService.search("spring boot");

        // then
        // 두 응답 모두 LLM 결과는 동일해야 함
        assertThat(resp1).isNotNull();
        assertThat(resp2).isNotNull();
        assertThat(resp1.getAnswer()).isEqualTo(llmAnswer);
        assertThat(resp2.getAnswer()).isEqualTo(llmAnswer);

        // ✅ 가장 중요한 부분:
        // SourceRepository / ContentFetcher / AnswerGenerator는
        // "정규화된 쿼리" 기준으로 딱 1번만 호출되어야 한다.
        verify(sourceRepository, times(1)).getSources(normalizedQuery);
        verify(contentFetcher, times(1)).fetchContents(sources);
        verify(answerGenerator, times(1))
                .generateAnswer(normalizedQuery, sources, contents);

        // 그 외의 이상 호출이 없는지(안 해도 되지만 있으면 좋음)
        verifyNoMoreInteractions(sourceRepository, contentFetcher, answerGenerator);
    }
}
