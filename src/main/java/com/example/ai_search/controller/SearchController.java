package com.example.ai_search.controller;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.service.SearchService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@AllArgsConstructor
@Slf4j
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/search";
    }

    @GetMapping("/search")
    public String searchPage(
            @RequestParam(name = "q", required = false)  String query,
            Model model
    ) {

        log.info("request start. traceId={}", MDC.get("traceId"));

        SearchResponseDto result = null;

        if (query != null && !query.isBlank()) {
            result = searchService.search(query);
        }

        model.addAttribute("query", query);
        model.addAttribute("result", result);


        log.info("request end. traceId={}", MDC.get("traceId"));
        // templates/search.html
        return "search";
    }


}
