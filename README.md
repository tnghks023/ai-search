# 🚀 AI Search – Spring Boot Mini Perplexity

Brave Search API + Gemini 기반 출처 기반 답변(Search → Crawl → Summarize) 서비스

Perplexity의 검색 파이프라인 구조(검색 → 크롤링 → 요약)을 Spring Boot로 직접 구현한 프로젝트입니다.
외부 API 장애·타임아웃·비용 등을 고려해 **retry / backoff / timeout / fallback / 캐싱 / 병렬 처리 / structured logging / 운영·개발 프로파일 분리**까지 반영한 **프로덕션 수준 아키텍처**로 구성되어 있습니다.

---

# 🌐 Live Demo (Render Free Tier)

👉 **[https://ai-search-7p8a.onrender.com](https://ai-search-7p8a.onrender.com)**

> ⚠️ Free Tier 환경으로 인해
>
> * cold start 지연(최초 요청 20~30초)
> * 초당 요청 제한
    >   등이 발생할 수 있습니다.
    >   내부적으로는 Redis Cloud + Brave API + Gemini LLM 까지 전부 실제로 호출합니다.

---

# ⭐ 핵심 기능 요약

## 1️⃣ Brave Search API + 안정적 WebClient 설계

* 상위 3개 문서(title / url / snippet) 수집
* 상태코드별 고급 예외 처리

    * **4xx → 즉시 fallback**
    * **5xx → retry(backoff) 후 최종 fallback**
* 전체 timeout 8초
* MDC 기반 traceId → X-Trace-Id 헤더로 Brave API에도 전파
* 장애 발생 시 비어 있는 리스트로 안전한 fallback

---

## 2️⃣ Jsoup 병렬 크롤링 (본문 텍스트 수집)

* 8개 스레드 풀
* URL별 Jsoup timeout 3초
* Future timeout 4초
* 일부 URL 실패해도 전체 파이프라인 유지
* LLM 입력 토큰 절약을 위해 텍스트 2,000자 이내로 제한

---

## 3️⃣ Gemini 기반 출처 기반 답변 생성

* Brave 결과 + Jsoup 본문 → 단일 컨텍스트 생성
* LLM 호출 정책

    * 2회 재시도
    * 시도당 timeout 20초
    * backoff(300ms → 600ms)
* 최종 실패 시 fallback 메시지 생성(검색 결과는 유지)
* 문장 끝에 `[1], [2]` 형태로 출처 번호 자동 삽입

---

## 4️⃣ 검색어 정규화 + **2단계 캐싱 전략 (Caffeine + Redis Cloud)**

### 🔍 QueryNormalizer

* 소문자 변환
* 중복 공백 제거
* `"  Spring   Boot  "` → `"spring boot"`

---

### 🧩 1단계: Caffeine (로컬 메모리 캐시)

| 캐시            | 내용          | TTL |
| ------------- | ----------- | --- |
| `sourceCache` | Brave 검색 결과 | 10분 |

* 초당 QPS 높아도 빠른 응답
* Brave API 비용 및 rate-limit 방지
* fallback 저장 금지 정책 유지

---

### 🧩 2단계: Redis Cloud (전역 분산 캐시)

| 캐시               | 내용           | TTL |
| ---------------- | ------------ | --- |
| `llmResultCache` | 최종 LLM 요약 결과 | 1시간 |

* Render 서버 여러 개여도 공유되는 글로벌 캐시
* JSON 직렬화(GenericJackson2JsonRedisSerializer) 적용
* DTO에 `Serializable` 필요 없음
* cold start 이후에도 응답 속도 개선

---

### 🛡 캐시 규칙: **fallback 절대 캐싱 금지**

* Brave 장애
* Jsoup 장애
* Gemini 장애
* 검색 결과 없음

→ fallback 결과는 저장하지 않음
→ 장애 상황이 재사용되는 문제 방지

---

### 📜 캐시 로깅

```
Cache HIT. key='spring boot'
Cache MISS. key='spring boot'
Cache PUT. key='spring boot'
Cache PUT skipped (fallback)
```

---

## 5️⃣ 전체 Search Pipeline

```
search(rawQuery)
    ↓ normalize(rawQuery)
    ↓ llmResultCache(Redis) 조회 (HIT → 즉시 반환)
    ↓ sourceCache(Caffeine) 조회 (MISS → Brave API)
    ↓ Jsoup 병렬 크롤링
    ↓ Gemini 요약 (timeout + retry + fallback)
    ↓ 정상 결과만 Redis 캐시에 저장
    ↓ 사용자에게 응답
```

---

# 🧭 운영 품질(Production Grade)

### ✔ resilience: timeout / retry / fallback 적용

각 단계가 독립적으로 실패해도 전체 서비스는 절대 멈추지 않음

### ✔ Structured Logging (JSON + traceId)

* dev: DEBUG 중심
* prod: INFO + 오류 전용 파일 + JSON
* 요청 단위 traceId 자동 부여 → 전체 파이프라인 추적 가능

### ✔ dev/prod 프로파일 분리

* prod: Redis + 최소 로그 + 안정성 중심
* dev: Caffeine + 상세 디버깅 중심

---

# 🧪 테스트 체계

### Unit + Integration Tests

* Brave 4xx fallback
* Brave 5xx retry(backoff)
* Jsoup 실패 fallback
* Gemini timeout + retry + fallback
* 검색어 정규화 테스트
* Caffeine 캐시 hit/miss 테스트
* Redis 캐시 hit/miss 테스트(mock)
* fallback 캐싱 금지 검증
* test 프로파일에서는 Redis 비활성화 (메모리 캐시 재정의)

> 외부 API 장애 시뮬레이션까지 모두 검증 완료.

---

# 🐳 Docker & Deploy (Render)

### Dockerfile

* Multi-stage build (JDK → JRE slim)
* 테스트 포함 빌드
* 최소 이미지로 최적화

### Render 환경 변수

```
SEARCH_API_KEY=xxxx
LLM_API_KEY=xxxx
REDIS_HOST=xxxx-redis.cloud.com
REDIS_PORT=6379
REDIS_PASSWORD=xxxx
SPRING_PROFILES_ACTIVE=prod
```

### Render + Redis Cloud 구조

* Render Web Service → Redis Cloud (TLS 연결)
* Redis를 통해 서버 인스턴스 간 캐시 공유
* LLM API 호출 최소화 → 비용 70~90% 절감

---

# 👨‍💻 Why This Project?

이 프로젝트는 단순 API 호출 예제가 아니라
**"실제 서비스를 운영할 수 있는 백엔드 아키텍처 경험"** 을 보여줍니다.

* 검색 → 크롤링 → LLM 요약이라는 멀티 스텝 RAG 구조
* 외부 API 장애를 견디는 resilient architecture
* 비용 최적화(정규화 + 캐싱 + context 축소)
* 운영 친화적 logging / profiling 설계
* DI + Mock 기반 테스트 주도 개발
* Redis Cloud + Caffeine 기반 2단계 캐시 아키텍처


---

