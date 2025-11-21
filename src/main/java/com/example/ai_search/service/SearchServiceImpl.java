package com.example.ai_search.service;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService{

    private final SourceRepository sourceRepository;
    private final ContentFetcher contentFetcher;
    private final AnswerGenerator answerGenerator;
    private final QueryNormalizer queryNormalizer;
    private final CacheManager cacheManager;

    @Override
    @Cacheable(
            value = "llmResultCache",
            key = "@queryNormalizer.normalize(#query)"  // 정규화된 쿼리로 캐시 키 사용
    )
    public SearchResponseDto search(String query) {

        String normalized = queryNormalizer.normalize(query);

        logCacheHitOrMiss(normalized);

        long totalStart = System.currentTimeMillis();
        log.info("Search pipeline start. raw='{}', normalized='{}'", query, normalized);

        long braveStart = System.currentTimeMillis();
        List<SourceDto> sources = sourceRepository.getSources(normalized);
        long braveMs = System.currentTimeMillis() - braveStart;

        // Brave + 캐시에서 아무 출처도 못 가져온 경우 → LLM/Jsoup 스킵
        if (sources == null || sources.isEmpty()) {
            long totalMs = System.currentTimeMillis() - totalStart;

            log.warn("No sources from sourceRepository. Skip Jsoup/LLM. query='{}', braveMs={}, totalMs={}",
                    normalized, braveMs, totalMs);

            String answer = """
                    죄송합니다, 현재는 외부 검색(Brave)에서 결과를 가져오지 못했습니다.
                    잠시 후 다시 시도해 주세요.
                    """;

            SearchResponseDto dto = new SearchResponseDto(answer, List.of());

            // 🔥 캐시에 저장될 값 로깅 (실패 fallback도 캐시에 들어감)
            logCachePut(normalized, dto);

            return dto;
        }
        long jsoupStart = System.currentTimeMillis();
        List<String> contents = contentFetcher.fetchContents(sources);
        long jsoupMs = System.currentTimeMillis() - jsoupStart;

        long llmStart = System.currentTimeMillis();
        String answer = answerGenerator.generateAnswer(normalized, sources, contents);
        long llmMs = System.currentTimeMillis() - llmStart;

        long totalMs = System.currentTimeMillis() - totalStart;

        log.info(
                "Search pipeline summary. query='{}', sources={}, braveMs={}, jsoupMs={}, llmMs={}, totalMs={}",
                normalized,
                sources.size(),
                braveMs,
                jsoupMs,
                llmMs,
                totalMs
        );

        SearchResponseDto dto = new SearchResponseDto(answer, sources);

        logCachePut(normalized, dto);

        return dto;
    }

    /**
     * 캐시 HIT/MISS 로깅
     * 주의: @Cacheable 프록시 구조상, 이 메서드는 "MISS일 때만" 실행되는 게 정상임.
     */
    private void logCacheHitOrMiss(String normalizedQuery) {
        Cache cache = cacheManager.getCache("llmResultCache");
        if (cache == null) {
            log.warn("Cache 'llmResultCache' not found. (cacheManager misconfigured?)");
            return;
        }

        Cache.ValueWrapper wrapper = cache.get(normalizedQuery);

        if (wrapper == null) {
            log.info("Cache MISS. key='{}'", normalizedQuery);
        } else {
            log.info("Cache HIT. key='{}'", normalizedQuery);
        }
    }

    /**
     * 캐시 저장 예정 로깅
     * 실제 put은 @Cacheable 프록시에서 처리하지만,
     * "어떤 key로, 어떤 요약 결과가 캐시에 들어가려는지"를 남겨둔다.
     */
    private void logCachePut(String normalizedQuery, SearchResponseDto dto) {
        try {
            int sourceCount = (dto.getSources() != null) ? dto.getSources().size() : 0;
            int answerLength = (dto.getAnswer() != null) ? dto.getAnswer().length() : 0;

            log.info(
                    "Cache PUT scheduled. cache='llmResultCache', key='{}', sources={}, answerLength={}",
                    normalizedQuery,
                    sourceCount,
                    answerLength
            );
        } catch (Exception e) {
            // 로깅 중 문제 생겨도 본 로직에는 영향 없게
            log.warn("Failed to log cache PUT info. key='{}', reason={}", normalizedQuery, e.toString());
        }
    }
}
