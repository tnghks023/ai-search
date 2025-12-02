package com.example.ai_search.service;


import com.example.ai_search.dto.BraveSearchResponse;
import com.example.ai_search.dto.SourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@CacheConfig(cacheManager = "caffeineCacheManager", cacheNames = "sourceCache")
public class BraveSourceRepository implements SourceRepository{

    private final WebClient braveWebClient;

    @Value("${search.api.key}")
    private String searchApiKey;

    @Value("${search.timeout-seconds:8}")
    private long searchTimeoutSeconds;

    @Override
    @Cacheable
    public List<SourceDto> getSources(String normalizedQuery) {

        long start = System.currentTimeMillis();
        log.info("Search requested. query='{}'", normalizedQuery);

        String traceId = MDC.get("traceId");

        Mono<List<SourceDto>> mono = braveWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/res/v1/web/search")
                        .queryParam("q", normalizedQuery)
                        .queryParam("count", 3)
                        .build()
                )
                .header("X-Subscription-Token", searchApiKey)
                .headers(headers -> {
                    if (traceId != null) {
                        headers.add("X-Trace-Id", traceId);
                    }
                })
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).flatMap(body -> {
                            log.warn("Brave 4xx error. body={}", body);
                            return Mono.error(new BraveClientException("잘못된 요청입니다 (Brave 4xx)."));
                        })
                )
                .onStatus(HttpStatusCode::is5xxServerError, resp ->
                        resp.bodyToMono(String.class).flatMap(body -> {
                            log.warn("Brave 5xx error. body={}", body);
                            return Mono.error(new BraveServerException("Brave 서버 오류 (5xx)."));
                        })
                )
                .bodyToMono(BraveSearchResponse.class)
                .log("BRAVE_WEBCLIENT")
                .doOnNext(resp ->
                        log.debug("Brave DTO response for query='{}', resultCount={}",
                                normalizedQuery,
                                resp.getWeb() != null && resp.getWeb().getResults() != null
                                        ? resp.getWeb().getResults().size()
                                        : 0
                        )
                )
                .map(this::toSources)
                .retryWhen(
                        Retry.backoff(2, Duration.ofMillis(200))
                                .filter(ex -> !(ex instanceof BraveClientException))
                )
                .timeout(Duration.ofSeconds(searchTimeoutSeconds))
                .onErrorResume(ex -> {
                    log.warn("Brave search failed, fallback to empty sources. reason={}", ex.toString());
                    return Mono.just(Collections.emptyList());
                });

        List<SourceDto> sources = mono.block();
        if (sources == null) {
            sources = List.of();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Brave search done. query='{}', resultCount={}, elapsedMs={}",
                normalizedQuery, sources.size(), elapsed);

        return sources;
    }

    private List<SourceDto> toSources(BraveSearchResponse response) {
        if (response == null || response.getWeb() == null || response.getWeb().getResults() == null) {
            return List.of();
        }
        List<SourceDto> list = new ArrayList<>();
        int idx = 1;
        for (BraveSearchResponse.Result r : response.getWeb().getResults()) {
            list.add(new SourceDto(
                    idx++,
                    r.getTitle(),
                    r.getUrl(),
                    r.getDescription()
            ));
        }
        return list;
    }

    static class BraveClientException extends RuntimeException {
        BraveClientException(String message) { super(message); }
    }

    static class BraveServerException extends RuntimeException {
        BraveServerException(String message) { super(message); }
    }
}
