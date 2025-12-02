package com.example.ai_search.service;

import com.example.ai_search.AiSearchApplication;
import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = {
                AiSearchApplication.class,
                SearchServiceImplIntegrationTest.TestCacheConfig.class
        },
        properties = {
                "SEARCH_API_KEY=dummy",
                "LLM_API_KEY=dummy"
        }
)
@ActiveProfiles("test")  // 🔥 CacheConfig(@Profile("!test")) 를 비활성화
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SearchServiceImplIntegrationTest {

    @TestConfiguration
    static class TestCacheConfig {

        // SearchServiceImpl 이 @CacheConfig(cacheManager = "redisCacheManager") 로 바라보는 놈
        @Bean("redisCacheManager")
        public CacheManager redisCacheManager() {
            SimpleCacheManager manager = new SimpleCacheManager();
            manager.setCaches(List.of(
                    new ConcurrentMapCache("llmResultCache")
            ));
            return manager;
        }
    }

    @MockitoBean
    SourceRepository sourceRepository;

    @MockitoBean
    ContentFetcher contentFetcher;

    @MockitoBean
    AnswerGenerator answerGenerator;

    @Autowired
    QueryNormalizer queryNormalizer;

    @Autowired
    SearchServiceImpl searchService;

    @Autowired
    @Qualifier("redisCacheManager")
    CacheManager cacheManager;   // 테스트에서 캐시 내용 확인용

    @Test
    @DisplayName("정규화된 쿼리 기준으로 캐시가 동작하여, 같은 의미의 쿼리는 한 번만 파이프라인을 탄다")
    void search_normalization_and_cache_work_together() {
        // given
        String rawQuery1 = "spring boot";
        String rawQuery2 = " Spring  boot  ";

        String normalized = queryNormalizer.normalize(rawQuery2);

        List<SourceDto> sources = List.of(
                new SourceDto(1, "Spring Boot Guide", "https://example.com", "스프링 부트 소개")
        );
        List<String> contents = List.of("본문 내용 일부");
        String llmAnswer = "이것은 스프링 부트에 대한 요약 답변입니다.";

        when(sourceRepository.getSources(normalized)).thenReturn(sources);
        when(contentFetcher.fetchContents(sources)).thenReturn(contents);
        when(answerGenerator.generateAnswer(normalized, sources, contents))
                .thenReturn(llmAnswer);

        // when
        SearchResponseDto resp1 = searchService.search(rawQuery1);
        SearchResponseDto resp2 = searchService.search(rawQuery2);

        // then
        assertThat(resp1).isNotNull();
        assertThat(resp2).isNotNull();
        assertThat(resp1.getAnswer()).isEqualTo(llmAnswer);
        assertThat(resp2.getAnswer()).isEqualTo(llmAnswer);

        // 정규화된 쿼리 기준으로 실제 파이프라인은 딱 한 번만 타야 한다
        verify(sourceRepository, times(1)).getSources(normalized);
        verify(contentFetcher, times(1)).fetchContents(sources);
        verify(answerGenerator, times(1)).generateAnswer(normalized, sources, contents);
        verifyNoMoreInteractions(sourceRepository, contentFetcher, answerGenerator);

        // 캐시에 값이 들어갔는지 확인 (key = 정규화된 쿼리)
        Cache cache = cacheManager.getCache("llmResultCache");
        assertThat(cache).isNotNull();
        SearchResponseDto cached = cache.get(normalized, SearchResponseDto.class);
        assertThat(cached).isNotNull();
        assertThat(cached.getAnswer()).isEqualTo(llmAnswer);
    }

    @Test
    @DisplayName("fallback 결과는 캐시에 저장되지 않고, 매 요청마다 파이프라인을 다시 탄다")
    void search_fallbackResult_isNotCached() {
        // given
        String rawQuery = "장애 테스트";

        String normalized = queryNormalizer.normalize(rawQuery);

        when(sourceRepository.getSources(normalized)).thenReturn(List.of());

        // when
        SearchResponseDto resp1 = searchService.search(rawQuery);
        SearchResponseDto resp2 = searchService.search(rawQuery);

        // then
        assertThat(resp1).isNotNull();
        assertThat(resp2).isNotNull();

        assertThat(resp1.getSources()).isEmpty();
        assertThat(resp2.getSources()).isEmpty();

        assertThat(resp1.getAnswer())
                .contains("외부 검색(Brave)에서 결과를 가져오지 못했습니다.");
        assertThat(resp2.getAnswer())
                .contains("외부 검색(Brave)에서 결과를 가져오지 못했습니다.");

        // fallback은 캐시에 안 들어가므로, 매 번 Brave를 다시 호출해야 한다 → 2번
        verify(sourceRepository, times(2)).getSources(normalized);
        verifyNoInteractions(contentFetcher, answerGenerator);

        // 캐시에 값이 없는지 확인
        Cache cache = cacheManager.getCache("llmResultCache");
        assertThat(cache).isNotNull();
        SearchResponseDto cached = cache.get(normalized, SearchResponseDto.class);
        assertThat(cached).isNull();
    }
}
