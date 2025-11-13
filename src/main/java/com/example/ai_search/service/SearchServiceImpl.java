package com.example.ai_search.service;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.dto.SourceDto;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService{

    private final WebClient webClient;
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
        String url = "https://api.search.brave.com/res/v1/web/search";

        Mono<Map> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.search.brave.com")
                        .path("/res/v1/web/search")
                        .queryParam("q", query)
                        .queryParam("count", 3)
                        .build()
                )
                .header("Accept", "application/json")
                .header("X-Subscription-Token", searchApiKey)
                .retrieve()
                .bodyToMono(Map.class);

        Map body = response.block();

        if (body == null || body.get("web") == null) {
            // 검색 결과 없거나 API 호출 실패
            return List.of();
        }

        Map web = (Map) body.get("web");
        List<Map<String, Object>> webResults =
                (List<Map<String, Object>>) web.get("results");
        if (webResults == null) {
            return List.of();
        }


        List<SourceDto> list = new ArrayList<>();
        int idx = 1;
        for (Map<String, Object> item : webResults) {
            String title = (String) item.get("title");
            String link = (String) item.get("url");
            String snippet = (String) item.get("description");

            list.add(new SourceDto(idx++, title, link, snippet));
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

}
