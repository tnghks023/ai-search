package com.example.ai_search;

import com.example.ai_search.dto.SearchResponseDto;
import com.example.ai_search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
public class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // SearchController가 주입받는 SearchService를 모킹해서 주입
    @MockitoBean
    private SearchService searchService;

    @Test
    @DisplayName("루트(/) 호출 시 /search로 리다이렉트된다")
    void rootRedirectsToSearch() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/search"));
    }

    @Test
    @DisplayName("쿼리 없이 /search 호출 시 검색 페이지가 200 OK로 렌더링된다")
    void getSearchPage_withoutQuery_returnsSearchView() throws Exception {
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attribute("query", (Object) null))
                .andExpect(model().attribute("result", (Object) null));
    }

    @Test
    @DisplayName("쿼리와 함께 /search 호출 시 SearchService가 호출되고 모델에 query/result가 담긴다")
    void getSearchPage_withQuery_callsServiceAndPopulatesModel() throws Exception {
        // given
        String query = "스프링 부트";
        SearchResponseDto dummy = new SearchResponseDto("dummy answer", List.of());

        Mockito.when(searchService.search(query)).thenReturn(dummy);

        // when & then
        mockMvc.perform(get("/search").param("q", query))
                .andExpect(status().isOk())
                .andExpect(view().name("search"))
                .andExpect(model().attribute("query", query))
                .andExpect(model().attribute("result", dummy));
    }
}
