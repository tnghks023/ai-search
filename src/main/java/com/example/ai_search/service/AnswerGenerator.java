package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;

import java.util.List;

public interface AnswerGenerator {
    String generateAnswer(String query, List<SourceDto> sources, List<String> contents);
}
