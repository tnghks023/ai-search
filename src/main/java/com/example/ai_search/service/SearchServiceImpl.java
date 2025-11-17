package com.example.ai_search.service;

import com.example.ai_search.dto.BraveSearchResponse;
import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService{

    private final WebClient braveWebClient;
    private final Client geminiClient;

    @Value("${search.api.key}")
    private String searchApiKey;

    @Value("${llm.model}")
    private String llmModel;

    @Override
    public SearchResponseDto search(String query) {

        // 1) Brave Search API 호출
        List<SourceDto> sources = callBraveSearch(query);

        // 2) 각 URL 본문 가져오기 (간단 버전: Jsoup + text() )
        List<String> contents = new ArrayList<>();
        for (SourceDto s : sources) {
            String text = fetchPageText(s.getUrl());
            contents.add(text);
        }

        // 3) LLM 호출하여, 출처 기반 답변 생성
        String answer = callLLM(query, sources, contents);

        return new SearchResponseDto(answer, sources);
    }

    // -------------------- 1) Brave 검색 ---------------------------
    private List<SourceDto> callBraveSearch(String query) {

        Mono<List<SourceDto>> mono = braveWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/res/v1/web/search")
                        .queryParam("q", query)
                        .queryParam("count", 3)
                        .build()
                )
                .header("X-Subscription-Token", searchApiKey)
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
                .map(this::toSources)                    // DTO -> List<SourceDto>
                .timeout(Duration.ofSeconds(2)) // 논리 타임아웃 (예: 2초 안에 못 끝나면 TimeoutException)
                .retryWhen( // 재시도(backoff) 설정
                        Retry.backoff(2, Duration.ofMillis(200)) // 최대 2번 재시도, 0.2초부터 backoff
                                .filter(ex -> !(ex instanceof BraveClientException))
                        // 4xx(클라이언트 에러)는 재시도해도 의미 없으니 제외
                )
                // 최종 fallback: 완전히 실패 시 빈 리스트 리턴
                .onErrorResume(ex -> {
                    log.warn("Brave search failed, fallback to empty sources. reason={}", ex.toString());
                    return Mono.just(Collections.emptyList());
                });

        // 최종적으로 동기 List로 받기
        List<SourceDto> sources = mono.block(Duration.ofSeconds(5)); // 전체 상한 5초 정도
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
        try {
            return Jsoup.connect(url)
                    .timeout(5000)
                    .get()
                    .text()
                    .substring(0, 2000);   // 2000자까지만
        } catch (Exception e) {
            log.warn("Failed to fetch page text. url={}, reason={}", url, e.toString());
            return "";
        }
    }

    // -------------------- 3) LLM(Gemini) 호출 ----------------------
    private String callLLM(String query, List<SourceDto> sources, List<String> contents) {

        // 1) 출처 + 내용 텍스트로 합치기
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
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

        // 3) Google Gen AI Java SDK로 호출
        GenerateContentResponse response =
                geminiClient.models.generateContent(
                        llmModel,      // 예: "gemini-2.5-flash"
                        prompt,
                        null           // 추가 설정 없으면 null
                );

        return response.text();
    }

    // 커스텀 예외
    static class BraveClientException extends RuntimeException {
        BraveClientException(String message) { super(message); }
    }

    static class BraveServerException extends RuntimeException {
        BraveServerException(String message) { super(message); }
    }

}
