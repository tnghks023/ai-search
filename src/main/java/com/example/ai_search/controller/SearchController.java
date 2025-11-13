package com.example.ai_search.controller;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.service.SearchService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@AllArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/search")
    public String searchPage(
            @RequestParam(name = "q", required = false)  String query,
            Model model
    ) {

        SearchResponseDto result = null;

        if (query != null && !query.isBlank()) {
            result = searchService.search(query);
        }

        model.addAttribute("query", query);
        model.addAttribute("result", result);

        // templates/search.html
        return "search";
    }


}
