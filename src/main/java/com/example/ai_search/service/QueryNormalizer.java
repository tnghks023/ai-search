package com.example.ai_search.service;

import org.springframework.stereotype.Component;

@Component
public class QueryNormalizer {

    public String normalize(String query) {
        if (query == null) return "";

        // 1. trim
        String q = query.trim();

        // 2. lower-case (영문일 때만)
        q = q.toLowerCase();

        // 3. 중복 공백 제거
        q = q.replaceAll("\\s+", " ");

        // 4. 끝에 생기는 불필요한 공백 제거
        q = q.trim();

        return q;
    }
}
