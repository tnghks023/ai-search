# 🚀 AI Search – Spring Boot Mini Perplexity

*Brave Search API + Gemini 기반 출처 기반 답변(Search → Crawl → Summarize) 서비스*

Perplexity의 검색 파이프라인 구조(검색 → 크롤링 → 요약)를 **Spring Boot로 직접 구현**한 프로젝트입니다.
외부 API 장애·타임아웃·비용 등을 고려해 **retry / backoff / timeout / fallback / 캐싱 / 병렬 처리 / structured logging / 운영-개발 프로파일 분리**까지 갖춘 실제 서비스 수준의 설계를 반영했습니다.

---

# ⭐ 핵심 기능 요약

## 1️⃣ Brave Search API + 안정적 WebClient 설계

* 상위 3개 문서(title / url / snippet) 수집
* 상태코드별 고급 예외 처리

  * **4xx → 재시도 없이 즉시 fallback**
  * **5xx → retry(backoff) 후 마지막에 fallback**
* 전체 timeout 8초
* MDC 기반 traceId → X-Trace-Id 헤더로 외부 API에도 전파
* 장애 발생 시 **빈 리스트 graceful fallback**

---

## 2️⃣ Jsoup 병렬 크롤링 (본문 텍스트 수집)

* 8개 스레드 풀
* URL당 Jsoup timeout 3초
* Future 논리 timeout 4초
* 일부 URL 실패해도 전체 파이프라인 유지
* 텍스트 최대 2,000자만 사용해 LLM 비용 절약

---

## 3️⃣ Gemini 기반 출처 기반 답변 생성

* Brave 결과 + Jsoup 본문을 합쳐 단일 컨텍스트 생성
* LLM 호출:

  * 2회 재시도
  * 시도당 timeout 20초
  * backoff(300ms → 600ms)
* 최종 실패 시 **fallback 메시지 생성(검색 결과는 유지)**
* 답변 문장 끝에 [1], [2] 형태로 출처 번호 포함

---

## 4️⃣ 검색어 정규화 + 2단계 캐싱 전략

### QueryNormalizer

* 공백 정리, lower-case, 중복 공백 제거
* `"   Spring   Boot  "` → `"spring boot"`

### Caffeine Cache

| 캐시                 | 내용          | TTL |
| ------------------ | ----------- | --- |
| **sourceCache**    | Brave 검색 결과 | 10분 |
| **llmResultCache** | 최종 LLM 답변   | 1시간 |

### 중요한 규칙: **fallback은 캐싱하지 않음**

* Brave 실패 / Gemini 실패 / 빈 검색 결과
  → 캐시 PUT 스킵
  → 장애 상황이 캐싱되어 사용자를 방해하지 않도록 설계

### 캐시 로깅

```
Cache HIT. key='spring boot'
Cache MISS. key='spring boot'
Cache PUT. key='spring boot'
Cache PUT skipped (fallback). key='spring boot'
```

---

## 5️⃣ Search Pipeline

```
search(rawQuery)
   ↓ normalize(rawQuery)
   ↓ llmResultCache 조회 (HIT → 즉시 반환)
   ↓ sourceCache 조회 (MISS → Brave API)
   ↓ Jsoup 병렬 크롤링
   ↓ Gemini 답변 생성 (timeout + retry + fallback)
   ↓ 정상 결과만 캐시 저장
   ↓ 반환
```

---

# 🧭 운영 품질 고려(Production-grade)

## ✔ timeout / retry / fallback

Brave / Jsoup / Gemini 각각 독립적으로 실패해도
**서비스 자체는 절대 장애 나지 않도록 설계**

## ✔ structured logging (JSON + MDC traceId)

* dev: DEBUG 기반 상세 추적
* prod: INFO + 오류 전용 파일 + JSON 로그 → ELK/Loki 수집 가능
* 요청 단위 트레이싱: `[traceId]` 자동 포함

## ✔ dev / prod 프로파일 완전 분리

* prod는 최소 로그 + 안정화 중심
* dev는 최대한 많이 보여주도록 구성
* 운영환경에서 API 키는 OS 환경 변수로 주입

---

# 🧪 테스트 체계

## 단위/통합 테스트 전체 구성

* **Brave 4xx fallback 테스트**
* **Brave 5xx retry(backoff) + fallback 테스트**
* **Jsoup 실패 fallback 테스트**
* **Gemini timeout + retry + fallback 테스트**
* **검색어 정규화 + 캐시 hit/miss 테스트**
* **fallback 캐시 스킵 테스트**
* **컨텍스트 로딩 테스트 (환경 변수 없이 dummy key로)**

→ 실제 장애 상황까지 모두 시뮬레이션 검증 완료

---

# 🐳 Docker & Deploy (Render)

### Dockerfile

* 멀티 스테이지 빌드
* 테스트 포함 빌드
* JDK → JRE 이미지로 슬림화

### Render 환경변수

```
SEARCH_API_KEY
LLM_API_KEY
SPRING_PROFILES_ACTIVE=prod
```

---

# 👨‍💻 Why This Project?

✔ 검색 → 크롤링 → LLM 요약이라는 **멀티 스텝 파이프라인**

✔ 외부 API 장애를 견디는 **resilient architecture**

✔ 비용 최적화(정규화 + 캐싱 + LLM 짧은 컨텍스트)

✔ 운영환경을 고려한 **logging / profiling / fallback**

✔ 테스트 가능한 구조(DI 기반 인터페이스 분리, mock 테스트)

까지 완성된 **실무용 백엔드 설계 경험**을 보여주는 포트폴리오입니다.

---
