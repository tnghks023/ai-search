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

    private static final String CACHE_NAME = "llmResultCache";

    private final SourceRepository sourceRepository;
    private final ContentFetcher contentFetcher;
    private final AnswerGenerator answerGenerator;
    private final QueryNormalizer queryNormalizer;
    private final CacheManager cacheManager;

    @Override
    public SearchResponseDto search(String query) {

        String normalized = queryNormalizer.normalize(query);

        long totalStart = System.currentTimeMillis();
        log.info("Search pipeline start. raw='{}', normalized='{}'", query, normalized);

        // 2) 캐시 조회 (HIT이면 바로 리턴, 단 fallback은 사용/저장 안 함)
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            SearchResponseDto cached = cache.get(normalized, SearchResponseDto.class);
            if (cached != null) {
                if (isFallback(cached)) {
                    log.info("Cache HIT with fallback, but ignoring. cache='{}', key='{}'", CACHE_NAME, normalized);
                    // 캐시에 들어있긴 하지만, fallback이면 사용하지 않고 다시 파이프라인 실행
                } else {
                    log.info("Cache HIT. cache='{}', key='{}', sources={}, answerLength={}",
                            CACHE_NAME,
                            normalized,
                            cached.getSources() != null ? cached.getSources().size() : 0,
                            cached.getAnswer() != null ? cached.getAnswer().length() : 0);
                    long totalMs = System.currentTimeMillis() - totalStart;
                    log.info("Search pipeline short-circuited by cache. normalized='{}', totalMs={}", normalized, totalMs);
                    return cached;
                }
            } else {
                log.info("Cache MISS. cache='{}', key='{}'", CACHE_NAME, normalized);
            }
        } else {
            log.warn("Cache '{}' not found. (CacheManager misconfigured?)", CACHE_NAME);
        }

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

            log.info("Cache PUT skipped (fallback or no sources). cache='{}', key='{}'", CACHE_NAME, normalized);

            return dto;
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

        // 4) 결과를 캐시에 넣을지 결정 (fallback이면 저장 X)
        if (cache != null) {
            if (isFallback(dto)) {
                log.info(
                        "Cache PUT skipped (fallback result). cache='{}', key='{}', sources={}, answerLength={}",
                        CACHE_NAME,
                        normalized,
                        dto.getSources() != null ? dto.getSources().size() : 0,
                        dto.getAnswer() != null ? dto.getAnswer().length() : 0
                );
            } else {
                cache.put(normalized, dto);
                log.info(
                        "Cache PUT. cache='{}', key='{}', sources={}, answerLength={}",
                        CACHE_NAME,
                        normalized,
                        dto.getSources() != null ? dto.getSources().size() : 0,
                        dto.getAnswer() != null ? dto.getAnswer().length() : 0
                );
            }
        }

        return dto;
    }

    /**
     * 이 SearchResponseDto가 "fallback 응답"인지 여부를 판단하는 헬퍼.
     * - sources가 비었으면 fallback으로 간주
     * - answer에 특정 fallback 문구가 포함되어도 fallback으로 간주
     */
    private boolean isFallback(SearchResponseDto dto) {
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
