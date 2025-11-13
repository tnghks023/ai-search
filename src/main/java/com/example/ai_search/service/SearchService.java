package com.example.ai_search.service;

import com.example.ai_search.dto.SearchResponseDto;

public interface SearchService {
    SearchResponseDto search(String query);
}
