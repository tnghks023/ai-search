package com.example.ai_search.service;

import com.example.ai_search.dto.SourceDto;

import java.util.List;

public interface SourceRepository {
    List<SourceDto> getSources(String query);
}
