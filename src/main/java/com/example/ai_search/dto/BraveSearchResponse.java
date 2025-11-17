package com.example.ai_search.dto;

import lombok.Data;

import java.util.List;

@Data
public class BraveSearchResponse {

    private Web web;

    // Web 객체
    @Data
    public static class Web {
        private List<Result> results;
    }

    // Result 객체
    @Data
    public static class Result {
        private String title;
        private String url;
        private String description;
    }

}
