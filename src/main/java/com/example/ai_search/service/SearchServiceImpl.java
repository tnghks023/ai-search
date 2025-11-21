package com.example.ai_search.service;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    @Cacheable(
            value = "llmResultCache",
            key = "@queryNormalizer.normalize(#query)"  // 정규화된 쿼리로 캐시 키 사용
    )
    public SearchResponseDto search(String query) {

        String normalized = queryNormalizer.normalize(query);

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

            return new SearchResponseDto(answer, List.of());
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

        return new SearchResponseDto(answer, sources);
    }
}
