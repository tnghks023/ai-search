package com.example.ai_search.service;

import com.example.ai_search.dto.BraveSearchResponse;
import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService{

    private final WebClient braveWebClient;
    private final Client geminiClient;
    private final ExecutorService jsoupExecutor =
            Executors.newFixedThreadPool(8); // jsoup 전용 풀

    private final ExecutorService llmExecutor =
            Executors.newFixedThreadPool(8); // LLM 전용 풀


    @Value("${search.api.key}")
    private String searchApiKey;

    @Value("${llm.model}")
    private String llmModel;

    @Value("${search.api.timeout-seconds:8}")
    private long searchTimeoutSeconds;

    @Value("${llm.timeout-seconds:12}")
    private long llmTimeoutSeconds;

    @Override
    public SearchResponseDto search(String query) {

        long totalStart  = System.currentTimeMillis();
        log.info("Search pipeline start. query='{}'", query);

        // 1) Brave Search API 호출
        long braveStart = System.currentTimeMillis();
        List<SourceDto> sources = callBraveSearch(query);
        long braveMs = System.currentTimeMillis() - braveStart;

        // 2) 각 URL 본문 가져오기 (간단 버전: Jsoup + text() )
        // Jsoup 병렬 텍스트 수집
        long jsoupStart = System.currentTimeMillis();
        List<String> contents = fetchPageTextsParallel(sources);
        long jsoupMs = System.currentTimeMillis() - jsoupStart;


        // 3) LLM 호출하여, 출처 기반 답변 생성
        long llmStart = System.currentTimeMillis();
        String answer = callLLM(query, sources, contents);
        long llmMs = System.currentTimeMillis() - llmStart;

        long totalMs = System.currentTimeMillis() - totalStart;
        int sourceCount = (sources != null ? sources.size() : 0);

        log.info(
                "Search pipeline summary. query='{}', sources={}, braveMs={}, jsoupMs={}, llmMs={}, totalMs={}",
                query,
                sourceCount,
                braveMs,
                jsoupMs,
                llmMs,
                totalMs
        );

        return new SearchResponseDto(answer, sources);
    }

    // -------------------- 1) Brave 검색 ---------------------------
    private List<SourceDto> callBraveSearch(String query) {

        long start = System.currentTimeMillis();
        log.info("Search requested. query='{}'", query);

        String traceId = MDC.get("traceId"); // 필터에서 넣은 값

        Mono<List<SourceDto>> mono = braveWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/res/v1/web/search")
                        .queryParam("q", query)
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
                // 4xx / 5xx 별도 처리
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
                .bodyToMono(BraveSearchResponse.class)   // JSON -> DTO
                // 전체 WebClient 체인 로그 (Reactor log)
                .log("BRAVE_WEBCLIENT")
                // 응답 resp 디버그 로그
                .doOnNext(resp ->
                        log.debug("Brave DTO response for query='{}', resultCount={}",
                                query,
                                resp.getWeb() != null && resp.getWeb().getResults() != null
                                        ? resp.getWeb().getResults().size()
                                        : 0
                        )
                )
                .map(this::toSources)                    // DTO -> List<SourceDto>
                .retryWhen( // 재시도(backoff) 설정
                        Retry.backoff(2, Duration.ofMillis(200)) // 최대 2번 재시도, 0.2초부터 backoff
                                .filter(ex -> !(ex instanceof BraveClientException))
                        // 4xx(클라이언트 에러)는 재시도해도 의미 없으니 제외
                )
                .timeout(Duration.ofSeconds(searchTimeoutSeconds))
                // 최종 fallback: 완전히 실패 시 빈 리스트 리턴
                .onErrorResume(ex -> {
                    log.warn("Brave search failed, fallback to empty sources. reason={}", ex.toString());
                    return Mono.just(Collections.emptyList());
                });

        // 최종적으로 동기 List로 받기
        List<SourceDto> sources = mono.block();

        log.info("Brave search done. query='{}', resultCount={}", query, sources != null ? sources.size() : 0);

        long elapsed = System.currentTimeMillis() - start;
        log.debug("Brave search success. elapsedMs={}",elapsed);
        
        return sources != null ? sources : List.of();
    }
    // Brave Search JSON → SourceDto 리스트로 변환하는 함수
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

    // -------------------- 2) HTML → 텍스트 파싱 --------------------
    private String fetchPageText(String url) {

        long start = System.currentTimeMillis();
        try {
             String text = Jsoup.connect(url)
                    .timeout(2000)
                    .get()
                    .text();

            // 너무 길면 2000자까지만
            if (text.length() > 2000) {
                text = text.substring(0, 2000);
            }

            long elapsed = System.currentTimeMillis() - start;
            log.debug("Jsoup fetch success. url='{}', elapsedMs={}, textLen={}",
                    url, elapsed, text.length());

            return text;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Failed to fetch page text. url={}, elapsedMs={} ,reason={}", url, elapsed, e.toString());
            return "";
        }
    }

    private List<String> fetchPageTextsParallel(List<SourceDto> sources) {

        // 1) 각 source마다 비동기 Jsoup 작업 만들기
        List<CompletableFuture<String>> futures = sources.stream()
                .map(source ->
                        CompletableFuture.supplyAsync(
                                () -> fetchPageText(source.getUrl()), // 기존 메서드 재사용
                                jsoupExecutor
                        )
                )
                .toList();

        // 2) 각 future에서 결과 받기 (여기서도 전체 timeout을 줄 수 있음)
        List<String> contents = new ArrayList<>(futures.size());

        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<String> f = futures.get(i);
            try {
                // 한 URL당 최대 3초까지만 기다림 (논리 timeout)
                String text = f.get(3, TimeUnit.SECONDS);
                contents.add(text != null ? text : "");
            } catch (TimeoutException e) {
                log.warn("Jsoup async timeout for source index={}", i);
                f.cancel(true); // 타임아웃 나면 취소 시도
                contents.add("");
            } catch (Exception e) {
                log.warn("Jsoup async failed for source index={}, reason={}", i, e.toString());
                contents.add("");
            }
        }

        return contents;
    }


    // -------------------- 3) LLM(Gemini) 호출 ----------------------
    private String callLLM(String query, List<SourceDto> sources, List<String> contents) {

        // 1) 출처 + 내용 텍스트로 합치기
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < Math.min(sources.size(), contents.size()); i++) {
            SourceDto s = sources.get(i);
            String c = contents.get(i);

            context.append("[%d] 제목: %s\nURL: %s\n내용 일부:\n%s\n\n"
                    .formatted(s.getId(), s.getTitle(), s.getUrl(), c));
        }

        // 2) Gemini에 줄 프롬프트 텍스트 하나로 만들기
        String prompt = """
                너는 '웹 출처 기반 답변 어시스턴트'이다.
                아래의 출처들만 근거로, 한국어로 답변해라.
                사실을 말할 때는 해당 출처 번호를 [1], [2] 처럼 문장 끝에 붙여라.
                확실하지 않은 내용은 '확실하지 않음'이라고 적어라.

                질문: %s

                출처들:
                %s
                """.formatted(query, context.toString());

        // timeout + retry + fallback + logging
        int maxAttempts = 2;             // 최대 2번 재시도
        long backoffMillis = 300L;       // 초기 backoff 0.3초

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            CompletableFuture<GenerateContentResponse> future = null;
            try {
                log.info("Gemini call start. attempt={}, query='{}', model={}",
                        attempt, query, llmModel);

                future = CompletableFuture.supplyAsync(() ->
                                geminiClient.models.generateContent(
                                        llmModel,
                                        prompt,
                                        null
                                ),
                        llmExecutor
                );


                GenerateContentResponse response =
                        future.get(llmTimeoutSeconds, TimeUnit.SECONDS);

                long elapsed = System.currentTimeMillis() - start;
                String answer = response.text();

                log.info("Gemini call success. attempt={}, elapsedMs={}, answerLength={}",
                        attempt,
                        elapsed,
                        (answer != null ? answer.length() : 0));

                log.debug("Gemini raw answer for query='{}': {}", query, answer);

                return (answer != null && !answer.isBlank())
                        ? answer
                        : "지금은 답변이 비어 있습니다. 나중에 다시 시도해 주세요.";
            }
            catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - start;
                log.warn("Gemini call timeout. attempt={}, elapsedMs={}, query='{}'",
                        attempt, elapsed, query);
                if (future != null) {
                    future.cancel(true); // 🔥 타임아웃 나면 취소 시도
                }
            }
            catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                log.warn("Gemini call failed. attempt={}, elapsedMs={}, query='{}', reason={}",
                        attempt, elapsed, query, e.toString());
                if (future != null) {
                    future.cancel(true); // 🔥 타임아웃 나면 취소 시도
                }
            }

            // 여기까지 왔다는 건 이번 attempt는 실패 → backoff 후 재시도
            if (attempt < maxAttempts) {
                try {
                    log.debug("Gemini retry sleep {} ms before next attempt", backoffMillis);
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Gemini retry sleep interrupted. aborting retries.");
                    break;
                }
                backoffMillis *= 2; // 지수 backoff (0.3초 → 0.6초)
            }
        }

        // 모든 재시도 실패 → 최종 fallback
        log.error("Gemini call failed after {} attempts. query='{}'", maxAttempts, query);

        return """
            죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.
            잠시 후 다시 시도해 주세요.
            (검색은 수행되었으므로 아래 출처들을 직접 참고해 주세요.)
            """;
    }

    // 커스텀 예외
    static class BraveClientException extends RuntimeException {
        BraveClientException(String message) { super(message); }
    }

    static class BraveServerException extends RuntimeException {
        BraveServerException(String message) { super(message); }
    }

}
