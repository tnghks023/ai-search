package com.example.ai_search.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SourceDto {

    private int id;
    private String title;
    private String url;
    private String snippet;

}
