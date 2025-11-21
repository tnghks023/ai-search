package com.example.ai_search.service;



import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class QueryNormalizerTest {

    private final QueryNormalizer normalizer = new QueryNormalizer();

    @Test
    @DisplayName("null 입력은 빈 문자열로 정규화된다")
    void normalize_null_returnsEmptyString() {
        String result = normalizer.normalize(null);
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("앞뒤 공백과 중복 공백이 정리되고 소문자로 바뀐다")
    void normalize_trimsAndSquashesSpacesAndLowercases() {
        String input = "   Hello   WORLD   ";
        String result = normalizer.normalize(input);
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    @DisplayName("한글 쿼리도 공백만 정규화되고 내용은 유지된다")
    void normalize_koreanQuery() {
        String input = "   스프링   부트   강의   ";
        String result = normalizer.normalize(input);
        assertThat(result).isEqualTo("스프링 부트 강의");
    }

    @Test
    @DisplayName("이미 정규화된 문자열은 그대로 유지된다")
    void normalize_alreadyNormalized() {
        String input = "spring boot";
        String result = normalizer.normalize(input);
        assertThat(result).isEqualTo("spring boot");
    }
}
