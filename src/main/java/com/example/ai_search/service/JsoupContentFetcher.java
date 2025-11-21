package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Component
@Slf4j
public class JsoupContentFetcher implements ContentFetcher{

    private final ExecutorService jsoupExecutor =
            Executors.newFixedThreadPool(8);

    @Override
    public List<String> fetchContents(List<SourceDto> sources) {

        List<CompletableFuture<String>> futures = sources.stream()
                .map(source ->
                        CompletableFuture.supplyAsync(
                                () -> fetchPageText(source.getUrl()),
                                jsoupExecutor
                        )
                )
                .toList();

        List<String> contents = new ArrayList<>(futures.size());

        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<String> f = futures.get(i);
            try {
                String text = f.get(3, TimeUnit.SECONDS);
                contents.add(text != null ? text : "");
            } catch (TimeoutException e) {
                log.warn("Jsoup async timeout for source index={}", i);
                f.cancel(true);
                contents.add("");
            } catch (Exception e) {
                log.warn("Jsoup async failed for source index={}, reason={}", i, e.toString());
                contents.add("");
            }
        }

        return contents;
    }

    private String fetchPageText(String url) {
        long start = System.currentTimeMillis();
        try {
            String text = Jsoup.connect(url)
                    .timeout(2000)
                    .get()
                    .text();

            if (text.length() > 2000) {
                text = text.substring(0, 2000);
            }

            long elapsed = System.currentTimeMillis() - start;
            log.debug("Jsoup fetch success. url='{}', elapsedMs={}, textLen={}",
                    url, elapsed, text.length());

            return text;

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Failed to fetch page text. url={}, elapsedMs={}, reason={}", url, elapsed, e.toString());
            return "";
        }
    }
}
