package com.example.ai_search.common.log;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(1) // 다른 필터보다 먼저 실행되게
public class MdcTraceIdFilter implements Filter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // 1) 외부에서 X-Trace-Id 헤더를 줬으면 그걸 쓰고
        // 2) 없으면 새 traceId 생성
        String traceId = httpRequest.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(TRACE_ID_KEY, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            // 요청 끝나면 꼭 지워주기
            MDC.remove(TRACE_ID_KEY);  // 또는 MDC.clear();
        }
    }
}
