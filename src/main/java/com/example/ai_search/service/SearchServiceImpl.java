package com.example.ai_search.service;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
@CacheConfig(cacheManager = "redisCacheManager")
public class SearchServiceImpl implements SearchService{

    private final SourceRepository sourceRepository;
    private final ContentFetcher contentFetcher;
    private final AnswerGenerator answerGenerator;
    private final QueryNormalizer queryNormalizer;

    @Override

    @Cacheable(
            cacheNames = "llmResultCache",
            key = "@queryNormalizer.normalize(#query)",
            unless = "#result == null || #root.target.isFallback(#result)"
    )
    public SearchResponseDto search(String query) {
        String normalized = queryNormalizer.normalize(query);
        return doSearchInternal(normalized);
    }

    private SearchResponseDto doSearchInternal(String normalized) {

        long totalStart = System.currentTimeMillis();
        log.info("Search pipeline start. normalized='{}'", normalized);

        long braveStart = System.currentTimeMillis();
        List<SourceDto> sources = sourceRepository.getSources(normalized);
        long braveMs = System.currentTimeMillis() - braveStart;

        if (sources == null || sources.isEmpty()) {
            long totalMs = System.currentTimeMillis() - totalStart;

            log.warn("No sources from sourceRepository. Skip Jsoup/LLM. query='{}', braveMs={}, totalMs={}",
                    normalized, braveMs, totalMs);

            String answer = """
                    죄송합니다, 현재는 외부 검색(Brave)에서 결과를 가져오지 못했습니다.
                    잠시 후 다시 시도해 주세요.
                    """;

            return new SearchResponseDto(answer, List.of()); // fallback → unless에 걸려서 캐시 X
        }
        long jsoupStart = System.currentTimeMillis();
        List<String> contents = contentFetcher.fetchContents(sources);
        long jsoupMs = System.currentTimeMillis() - jsoupStart;

        long llmStart = System.currentTimeMillis();
        String answer = answerGenerator.generateAnswer(normalized, sources, contents);
        long llmMs = System.currentTimeMillis() - llmStart;

        long totalMs = System.currentTimeMillis() - totalStart;

        SearchResponseDto dto = new SearchResponseDto(answer, sources);

        log.info(
                "Search pipeline summary. query='{}', sources={}, braveMs={}, jsoupMs={}, llmMs={}, totalMs={}",
                normalized,
                sources.size(),
                braveMs,
                jsoupMs,
                llmMs,
                totalMs
        );


        return dto;
    }

    /**
     * 이 SearchResponseDto가 "fallback 응답"인지 여부를 판단하는 헬퍼.
     * - sources가 비었으면 fallback으로 간주
     * - answer에 특정 fallback 문구가 포함되어도 fallback으로 간주
     */
    public  boolean isFallback(SearchResponseDto dto) {
        if (dto == null) return true;

        // 1) 출처가 하나도 없으면 fallback으로 본다 (Brave 실패 케이스 등)
        if (dto.getSources() == null || dto.getSources().isEmpty()) {
            return true;
        }

        // 2) answer 내용으로 fallback 여부 추가 판별 (Gemini 실패 케이스)
        String answer = dto.getAnswer();
        if (answer == null) return true;

        if (answer.contains("현재는 질문에 대한 답변을 생성할 수 없습니다.")
                || answer.contains("외부 검색(Brave)에서 결과를 가져오지 못했습니다.")) {
            return true;
        }

        return false;
    }
}
