package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

class SearchServiceImplBraveFallbackTest {

    /**
     * Brave가 4xx를 반환하면:
     * - BraveClientException 발생
     * - retry 대상에서 제외
     * - onErrorResume에서 빈 리스트 반환
     */
    @Test
    @DisplayName("Brave 4xx 응답 시 빈 리스트로 fallback 한다")
    void callBraveSearch_returnsEmptyList_on4xx() throws Exception {
        // given
        ExchangeFunction fourXxExchange = request ->
                Mono.just(ClientResponse.create(BAD_REQUEST)
                        .body("{\"error\":\"4xx\"}")
                        .build());

        WebClient braveWebClient = WebClient.builder()
                .exchangeFunction(fourXxExchange)
                .build();

        // BraveSourceRepository 인스턴스 직접 생성
        BraveSourceRepository repo = new BraveSourceRepository(braveWebClient);

        // @Value 값 강제로 주입
        ReflectionTestUtils.setField(repo, "searchApiKey", "dummy");
        ReflectionTestUtils.setField(repo, "searchTimeoutSeconds", 3L);

//        // private callBraveSearch 호출 준비
//        Method method = BraveSourceRepository.class
//                .getDeclaredMethod("callBraveSearch", String.class);
//        method.setAccessible(true);
//        @SuppressWarnings("unchecked")
//        List<SourceDto> sources = (List<SourceDto>) method.invoke(repo, "테스트 쿼리");

        // when
        List<SourceDto> sources = repo.getSources("테스트 쿼리");

        // then
        assertThat(sources).isNotNull();
        assertThat(sources).isEmpty(); // 4xx → fallback → 빈 리스트
    }

    /**
     * Brave가 5xx를 반환하면:
     * - BraveServerException 발생
     * - retry(backoff) 2회 시도
     * - 최종적으로 onErrorResume에서 빈 리스트 반환
     */
    @Test
    @DisplayName("Brave 5xx 응답 시 재시도 후 빈 리스트로 fallback 한다")
    void callBraveSearch_retriesAndReturnsEmptyList_on5xx() throws Exception {
        // given
        AtomicInteger callCount = new AtomicInteger(0);

        ExchangeFunction fiveXxExchange = request -> {
            callCount.incrementAndGet();
            return Mono.just(ClientResponse.create(INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"5xx\"}")
                    .build());
        };

        WebClient braveWebClient = WebClient.builder()
                .exchangeFunction(fiveXxExchange)
                .build();

        BraveSourceRepository repo = new BraveSourceRepository(braveWebClient);

        ReflectionTestUtils.setField(repo, "searchApiKey", "dummy-key");

        ReflectionTestUtils.setField(repo, "searchTimeoutSeconds", 3L);  // or 5L

        long start = System.currentTimeMillis();;
        List<SourceDto> sources = repo.getSources("테스트 쿼리");
        long elapsed = System.currentTimeMillis() - start;


        // then
        assertThat(sources).isNotNull();
        assertThat(sources).isEmpty(); // 5xx → retry 후 fallback → 빈 리스트

        // retry(backoff) 정책: 최초 호출 + 2회 재시도 = 최소 3회 이상 호출돼야 함
        assertThat(callCount.get()).isGreaterThanOrEqualTo(3);

        // timeout(8초) 안에 끝나는지 (아주 빡세게 볼 필요는 없고 대략적인 sanity check)
        assertThat(elapsed).isLessThan(Duration.ofSeconds(8).toMillis());
    }
}
