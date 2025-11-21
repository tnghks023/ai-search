package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiAnswerGenerator implements AnswerGenerator{

    private final Client geminiClient;

    private final ExecutorService llmExecutor =
            Executors.newFixedThreadPool(8);

    @Value("${llm.model}")
    private String llmModel;

    @Value("${llm.timeout-seconds:12}")
    private long llmTimeoutSeconds;

    @Override
    public String generateAnswer(String query, List<SourceDto> sources, List<String> contents) {

        StringBuilder context = new StringBuilder();

        for (int i = 0; i < Math.min(sources.size(), contents.size()); i++) {
            SourceDto s = sources.get(i);
            String c = contents.get(i);

            context.append("[%d] 제목: %s\nURL: %s\n내용 일부:\n%s\n\n"
                    .formatted(s.getId(), s.getTitle(), s.getUrl(), c));
        }

        String prompt = """
                너는 '웹 출처 기반 답변 어시스턴트'이다.
                아래의 출처들만 근거로, 한국어로 답변해라.
                사실을 말할 때는 해당 출처 번호를 [1], [2] 처럼 문장 끝에 붙여라.
                확실하지 않은 내용은 '확실하지 않음'이라고 적어라.

                질문: %s

                출처들:
                %s
                """.formatted(query, context.toString());

        int maxAttempts = 2;
        long backoffMillis = 300L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            CompletableFuture<GenerateContentResponse> future = null;
            try {
                log.info("Gemini call start. attempt={}, query='{}', model={}",
                        attempt, query, llmModel);

                future = CompletableFuture.supplyAsync(
                        () -> geminiClient.models.generateContent(
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
            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - start;
                log.warn("Gemini call timeout. attempt={}, elapsedMs={}, query='{}'",
                        attempt, elapsed, query);
                if (future != null) {
                    future.cancel(true);
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                log.warn("Gemini call failed. attempt={}, elapsedMs={}, query='{}', reason={}",
                        attempt, elapsed, query, e.toString());
                if (future != null) {
                    future.cancel(true);
                }
            }

            if (attempt < maxAttempts) {
                try {
                    log.debug("Gemini retry sleep {} ms before next attempt", backoffMillis);
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Gemini retry sleep interrupted. aborting retries.");
                    break;
                }
                backoffMillis *= 2;
            }
        }
        log.error("Gemini call failed after {} attempts. query='{}'", maxAttempts, query);

        return """
            죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.
            잠시 후 다시 시도해 주세요.
            (검색은 수행되었으므로 아래 출처들을 직접 참고해 주세요.)
            """;
    }
}
