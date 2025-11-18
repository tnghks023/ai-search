

# AI Search – Spring Boot Mini Perplexity

Brave Search API + Google Gemini LLM 기반의 **출처 기반 답변(Search → Crawl → Summarize)** 서비스입니다.

사용자가 질의를 보내면,

1. Brave Search API로 검색 결과를 가져오고  
2. 필요 시 각 URL을 Jsoup로 크롤링하여 텍스트를 수집한 뒤  
3. Google Gemini에 컨텍스트와 함께 전달해  
4. **출처 번호([1], [2], …)가 명시된 근거 기반 답변**을 생성합니다.

외부 API 특성에 맞춘 **timeout / retry / fallback / 병렬 처리 / structured logging / dev·prod 프로파일 분리**까지 포함해  
Perplexity 유사 검색 파이프라인을 Spring Boot로 구현한 프로젝트입니다.

---

## 🚀 Features

### 1️⃣ Brave Search API 기반 웹 검색

- Brave Search API `/res/v1/web/search` 호출
- `q`, `count=3` 파라미터로 상위 검색 결과 3개 수집
- 각 결과에서 **제목(title) / URL / description(snippet)** 추출
- Brave가 4xx/5xx를 반환하는 경우를 명확히 구분하여 처리
  - 4xx: `BraveClientException` 발생, 재시도 X
  - 5xx: `BraveServerException` 발생, 재시도 대상

검색 결과는 내부에서 `SourceDto(id, title, url, snippet)` 리스트로 변환되어 LLM에 전달됩니다.

---

### 2️⃣ Jsoup 기반 HTML 본문 텍스트 수집 (병렬 처리)

- 각 검색 결과의 URL에 대해 **Jsoup**로 HTML을 가져와 `.text()`로 본문 텍스트 추출
- 텍스트는 **최대 2,000자까지만 사용**해 LLM 입력 길이를 제어
- `SearchServiceImpl` 내부에서:

  - `jsoupExecutor = Executors.newFixedThreadPool(8)`  
    → Jsoup 요청을 위한 **전용 스레드 풀 8개** 운영
  - `fetchPageTextsParallel()`에서 `CompletableFuture`로 병렬 실행
  - 개별 URL마다:
    - Jsoup HTTP 타임아웃: 2초
    - Future 논리 타임아웃: 3초 (`f.get(3, TimeUnit.SECONDS)`)

- 실패/타임아웃 시:
  - WARN 로그 기록
  - 해당 URL은 빈 문자열 `""`로 대체 (파이프라인 자체는 계속 진행)

---

### 3️⃣ Gemini LLM 기반 출처 기반 답변 생성

- Brave 검색 결과 + Jsoup 텍스트를 모아서 하나의 컨텍스트 문자열로 구성:

  ```text
  [1] 제목: ...
  URL: ...
  내용 일부:
  (Jsoup로 가져온 본문 일부)

  [2] 제목: ...
  ...
```

* 최종 프롬프트:

  ```text
  너는 '웹 출처 기반 답변 어시스턴트'이다.
  아래의 출처들만 근거로, 한국어로 답변해라.
  사실을 말할 때는 해당 출처 번호를 [1], [2] 처럼 문장 끝에 붙여라.
  확실하지 않은 내용은 '확실하지 않음'이라고 적어라.

  질문: {query}

  출처들:
  {context}
  ```

* LLM 호출은 `SearchServiceImpl.callLLM()`에서 수행:

    * `llmExecutor = Executors.newFixedThreadPool(8)`
      → LLM 호출 전용 스레드 풀
    * 최대 **2회 재시도**
    * 각 시도마다 **논리 타임아웃 4초** (`future.get(4, TimeUnit.SECONDS)`)
    * 시도 실패 시:

        * `TimeoutException` → WARN 로그 + future 취소
        * 기타 예외 → WARN 로그 + future 취소
        * 다음 시도 전 `backoffMillis`(300ms → 600ms) 만큼 sleep

* 최종적으로 모든 시도 실패 시:

  ```text
  죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.
  잠시 후 다시 시도해 주세요.
  (검색은 수행되었으므로 아래 출처들을 직접 참고해 주세요.)
  ```

  를 반환하여, **검색 결과는 유지하면서도 LLM 장애를 우회**합니다.

---

### 4️⃣ WebClient + Brave Search 안정성 설계

`SearchServiceImpl.callBraveSearch()`에서 WebClient를 사용해 Brave API를 호출합니다.

* `X-Subscription-Token` 헤더로 API 키 전달

* `MDC`에 저장된 `traceId`를 `X-Trace-Id` 헤더로 전파

* 응답 상태 코드 별 처리:

    * **4xx (클라이언트 오류)**

        * 응답 바디를 문자열로 읽어 WARN 로그
        * `BraveClientException` 발생 → **재시도 대상에서 제외**

    * **5xx (서버 오류)**

        * 응답 바디를 문자열로 읽어 WARN 로그
        * `BraveServerException` 발생 → **재시도 대상**

* Reactor `Retry.backoff(2, Duration.ofMillis(200))`

    * 최대 2번 재시도 (즉, 최초 호출 + 재시도 2번 = 최대 3회 시도)
    * 200ms부터 backoff
    * `BraveClientException`(4xx)은 재시도하지 않도록 `filter` 적용

* 체인 전체에 `timeout(Duration.ofSeconds(3))` 적용

* 최종적으로 모든 시도가 실패하면:

    * WARN 로그 출력
    * **빈 리스트 `Collections.emptyList()`를 fallback**으로 반환
      → LLM에 전달할 출처가 없더라도, 파이프라인이 예외 없이 끝까지 동작하게 함

---

### 5️⃣ Spring MVC + Thymeleaf UI

* `/search?q=키워드` 형태의 GET 요청으로 검색 수행
* `SearchResponseDto(answer, sources)`를 모델에 담아 Thymeleaf 템플릿(`search.html`)에 전달
* 화면에는:

    * LLM 답변
    * 출처 리스트(제목, URL, snippet)
      가 동시에 렌더링됩니다.

---

## 🔍 Search Pipeline 상세

```text
SearchServiceImpl.search(query)
   ├─ 1) callBraveSearch(query)
   │    ├─ WebClient GET /res/v1/web/search
   │    ├─ X-Subscription-Token, X-Trace-Id 헤더 추가
   │    ├─ 4xx → BraveClientException (retry X)
   │    ├─ 5xx → BraveServerException (retry O)
   │    ├─ retry(backoff) + timeout(3s)
   │    └─ List<SourceDto>로 변환
   │
   ├─ 2) fetchPageTextsParallel(sources)
   │    ├─ jsoupExecutor(8 threads)로 병렬 Jsoup 요청
   │    ├─ URL당 Jsoup timeout 2s
   │    ├─ Future당 논리 timeout 3s
   │    └─ 실패 시 빈 문자열("")로 대체
   │
   ├─ 3) callLLM(query, sources, contents)
   │    ├─ 출처 + 내용(context) 문자열 구성
   │    ├─ llmExecutor(8 threads)에서 비동기 호출
   │    ├─ 시도당 논리 timeout 4s
   │    ├─ 최대 2회 시도 + backoff(0.3s → 0.6s)
   │    └─ 최종 실패 시 fallback 메시지 반환
   │
   └─ 4) SearchResponseDto(answer, sources) 반환
```

---

## 🧭 Environment & Logging

### 🔹 MDC 기반 traceId 전파

* 애플리케이션 진입 시 (예: 필터/인터셉터) `MDC.put("traceId", ...)`로 traceId를 설정한다고 가정
* `SearchServiceImpl.callBraveSearch()`에서:

    * `String traceId = MDC.get("traceId");`
    * Brave API 호출 시 `X-Trace-Id` 헤더로 전달
* `logback-spring.xml`의 패턴:

  ```text
  %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n
  ```

  → 모든 로그에 `[traceId]`가 함께 출력되도록 구성

---

### 🔹 Logback 공통 설정

`src/main/resources/logback-spring.xml`:

* **공통 프로퍼티**

    * `LOG_PATH = logs`
    * `LOG_PATTERN = 날짜 / 스레드 / 레벨 / [traceId] / 로거 / 메시지`

* **CONSOLE**

    * 개발자용 콘솔 출력
    * `LOG_PATTERN` 적용

* **FILE (RollingFileAppender)**

    * `logs/app.log`
    * `SizeAndTimeBasedRollingPolicy`

        * 파일 이름: `logs/app-YYYY-MM-DD.i.log`
        * 파일당 최대 10MB
        * 최대 30일 보관

* **ERROR_FILE (RollingFileAppender)**

    * `logs/error.log`
    * LevelFilter: **ERROR만 기록**
    * `logs/error-YYYY-MM-DD.i.log`
    * 파일당 최대 10MB
    * 최대 30일 보관

* **기본 root 로거**

    * level: `INFO`
    * appender: `CONSOLE`, `FILE`, `ERROR_FILE`, `JSON_FILE` (프로파일별 정의)

---

### 🔹 dev 프로파일 (`spring.profiles.active=dev`)

```xml
<springProfile name="dev">
    <!-- JSON_FILE: logs/app-json-dev.log -->
    <!-- LogstashEncoder로 JSON 형태 로그 출력 -->
    <!-- customFields: {"app":"ai-search","env":"dev"} -->
    <!-- includeMdc=true 로 traceId 등 MDC 포함 -->

    <root level="DEBUG">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
        <appender-ref ref="ERROR_FILE"/>
        <appender-ref ref="JSON_FILE"/>
    </root>

    <logger name="org.thymeleaf" level="WARN"/>
    <logger name="BRAVE_WEBCLIENT" level="DEBUG"/>
    <logger name="reactor.netty.http.client" level="DEBUG"/>
    <logger name="org.springframework.web.reactive.function.client" level="DEBUG"/>
    <logger name="com.example.ai_search" level="DEBUG"/>
</springProfile>
```

* 개발 환경에서는:

    * 전체 레벨 `DEBUG`
    * Brave WebClient 체인의 `.log("BRAVE_WEBCLIENT")` 로그를 DEBUG로 상세 출력
    * Reactor Netty / WebClient 디버깅 활성화
    * JSON 로그(`app-json-dev.log`)에 **traceId 포함한 구조화 로그**를 남길 수 있음

---

### 🔹 prod 프로파일 (`spring.profiles.active=prod`)

```xml
<springProfile name="prod">
    <!-- JSON_FILE: logs/app-json.log (ELK/Loki 연동용) -->

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
        <appender-ref ref="ERROR_FILE"/>
    </root>

    <logger name="org.thymeleaf" level="ERROR"/>
    <logger name="BRAVE_WEBCLIENT" level="INFO"/>
    <logger name="reactor.netty.http.client" level="WARN"/>
    <logger name="org.springframework.web.reactive.function.client" level="WARN"/>
    <logger name="com.example.ai_search" level="INFO"/>
</springProfile>
```

* 운영 환경에서는:

    * 전체 레벨 `INFO`
    * WebClient / Reactor 로그는 `WARN` 이상만 출력
    * Brave WebClient 체인 로그는 `INFO` 이상만 수집
    * ERROR 전용 로그 파일로 심각한 오류만 분리 관리

---

## 🧱 Tech Stack

* **Language**

    * Java 21

* **Backend**

    * Spring Boot 3.5.7
    * Spring MVC
    * Spring WebFlux (WebClient)
    * Thymeleaf

* **AI / 외부 연동**

    * Brave Search API
    * Google Gemini (Java SDK – `com.google.genai:google-genai`)
    * Jsoup (HTML 파싱)

* **Logging**

    * Logback
    * Logstash Logback Encoder (JSON 로그)

---

## 📂 Project Structure

```text
src/main/java/com/example/ai_search/
├── AiSearchApplication.java
├── controller/
│   └── SearchController.java
├── service/
│   ├── SearchService.java
│   └── SearchServiceImpl.java
├── config/
│   ├── WebClientConfig.java       # Brave WebClient 설정 (별도 클래스)
│   └── GeminiConfig.java          # Gemini Client 설정 (별도 클래스)
├── dto/
│   ├── BraveSearchResponse.java
│   ├── SearchResponseDto.java
│   └── SourceDto.java
├── common/
│   ├── log/
│   │   └── MdcTraceIdFilter.java
└── resources/
    ├── templates/
    │   └── search.html
    ├── application.properties.example
    ├── logback-spring.xml
    ├── application-dev.yml
    ├── application-prod.yml   
    └── (실제 application.properties는 .gitignore 대상)
```

---

## 🔐 API Key Management

### 1) `.gitignore` 예시

```gitignore
src/main/resources/application.properties
.env
```

### 2) GitHub에는 템플릿만 커밋

```text
src/main/resources/application.properties.example
```

예시:

```properties
search.api.key=${SEARCH_API_KEY}
llm.api.key=${LLM_API_KEY}
llm.model=gemini-2.0-flash-lite   # 예시
```

### 3) 운영 환경에서는 OS 환경 변수 사용

**Windows**

```powershell
setx SEARCH_API_KEY "your_brave_key"
setx LLM_API_KEY "your_gemini_key"
```

**macOS / Linux**

```bash
export SEARCH_API_KEY=your_brave_key
export LLM_API_KEY=your_gemini_key
```

---

## 🛠 How to Run

```bash
# 1. 의존성 설치
./gradlew clean build

# 2. 프로퍼티 템플릿 복사
cp src/main/resources/application.properties.example \
   src/main/resources/application.properties

# 3. 환경 변수 설정 (SEARCH_API_KEY, LLM_API_KEY)

# 4. 애플리케이션 실행 (dev 프로파일 예시)
./gradlew bootRun --args='--spring.profiles.active=dev'
```

접속:

```text
http://localhost:8080/search?q=스프링부트
```

---

## 📈 Logging Example

```text
INFO  c.e.a.service.SearchServiceImpl - Search pipeline start. query='스프링부트'
INFO  c.e.a.service.SearchServiceImpl - Search requested. query='스프링부트'
DEBUG BRAVE_WEBCLIENT - [HTTP 요청/응답 로그 ...]
DEBUG c.e.a.service.SearchServiceImpl - Brave DTO response for query='스프링부트', resultCount=3
INFO  c.e.a.service.SearchServiceImpl - Brave search done. query='스프링부트', resultCount=3
DEBUG c.e.a.service.SearchServiceImpl - Brave search success. elapsedMs=1417
DEBUG c.e.a.service.SearchServiceImpl - Jsoup fetch success. url='https://...', elapsedMs=544, textLen=2000
INFO  c.e.a.service.SearchServiceImpl - Gemini call start. attempt=1, query='스프링부트', model=...
INFO  c.e.a.service.SearchServiceImpl - Gemini call success. attempt=1, elapsedMs=2415, answerLength=428
INFO  c.e.a.service.SearchServiceImpl - Search pipeline done. query='스프링부트', totalMs=4729
```

---

## 📌 LICENSE

MIT License

```