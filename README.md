
# AI Search – Spring Boot Mini Perplexity

Brave Search API + Google Gemini LLM 기반의  
**출처 기반 답변(Search → Crawl → Summarize)** 서비스입니다.

사용자가 질의를 보내면,

1. Brave Search API로 검색 결과를 가져오고  
2. 각 URL을 Jsoup로 크롤링하여 텍스트를 수집한 뒤  
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
- 응답 상태별로 명확하게 분기 처리:

  - **4xx**
    - 응답 바디를 로그로 남기고 `BraveClientException` 발생
    - **재시도 대상에서 제외**
  - **5xx**
    - 응답 바디를 로그로 남기고 `BraveServerException` 발생
    - **재시도 대상**

- Reactor `Retry.backoff(2, Duration.ofMillis(200))` 적용
  - 최대 2번 재시도 (최대 3회 시도)
  - 200ms부터 backoff
- WebClient 체인 전체에 `timeout(Duration.ofSeconds(3))` 적용
- 최종적으로 모든 시도가 실패하면:
  - WARN 로그 출력
  - **빈 리스트(`Collections.emptyList()`)를 fallback**으로 반환  
    → LLM에 전달할 출처가 없어도 파이프라인은 예외 없이 끝까지 동작

검색 결과는 내부에서 `SourceDto(id, title, url, snippet)` 리스트로 변환되어 LLM에 전달됩니다.

---

### 2️⃣ Jsoup 기반 HTML 본문 텍스트 수집 (병렬 처리)

- 각 검색 결과의 URL에 대해 **Jsoup**로 HTML을 가져와 `.text()`로 본문 텍스트 추출
- 텍스트는 **최대 2,000자까지만 사용**해 LLM 입력 길이를 제어
- `SearchServiceImpl` 내부에서:

  - `jsoupExecutor = Executors.newFixedThreadPool(8)`  
    → Jsoup 요청을 위한 **전용 스레드 풀(8 threads)** 운영
  - `fetchPageTextsParallel()`에서 `CompletableFuture`로 병렬 실행
  - 개별 URL마다:
    - Jsoup HTTP 타임아웃: **2초**
    - Future 논리 타임아웃: **3초** (`f.get(3, TimeUnit.SECONDS)`)

- 실패 / 타임아웃 시:
  - WARN 로그 기록
  - 해당 URL은 **빈 문자열 `""`로 대체**  
    → 일부 URL이 실패해도 전체 검색/요약 파이프라인은 그대로 유지

---

### 3️⃣ Gemini LLM 기반 출처 기반 답변 생성

Brave 검색 결과 + Jsoup 텍스트를 합쳐 하나의 컨텍스트 문자열로 구성합니다.

```text
[1] 제목: ...
URL: ...
내용 일부:
(본문 텍스트 일부)

[2] 제목: ...
...
````

최종 프롬프트 예시는 다음과 같습니다.

```text
너는 '웹 출처 기반 답변 어시스턴트'이다.
아래의 출처들만 근거로, 한국어로 답변해라.
사실을 말할 때는 해당 출처 번호를 [1], [2] 처럼 문장 끝에 붙여라.
확실하지 않은 내용은 '확실하지 않음'이라고 적어라.

질문: {query}

출처들:
{context}
```

LLM 호출은 `SearchServiceImpl.callLLM()`에서 수행하며:

* `llmExecutor = Executors.newFixedThreadPool(8)`
  → LLM 호출 전용 스레드 풀
* 최대 **2회 재시도**
* 시도당 **논리 타임아웃 4초** (`future.get(4, TimeUnit.SECONDS)`)
* 시도 실패 시:

    * `TimeoutException` → WARN 로그 + future 취소
    * 기타 예외 → WARN 로그 + future 취소
    * 다음 시도 전 `backoffMillis` (300ms → 600ms) 만큼 sleep

모든 시도 실패 시에는 아래와 같은 **fallback 메시지**를 반환합니다.

```text
죄송합니다, 현재는 질문에 대한 답변을 생성할 수 없습니다.
잠시 후 다시 시도해 주세요.
(검색은 수행되었으므로 아래 출처들을 직접 참고해 주세요.)
```

→ **검색 결과는 유지하면서도 LLM 장애를 우회**하는 구조입니다.

---

### 4️⃣ WebClient + Brave Search 안정성 설계

`SearchServiceImpl.callBraveSearch()`에서 WebClient를 사용해 Brave API를 호출합니다.

* `X-Subscription-Token` 헤더로 Brave API Key 전달
* `MDC`에 저장된 `traceId`를 `X-Trace-Id` 헤더로 전파

에러 처리 / 재시도 / 타임아웃 / fallback 로직은 위 Features에서 설명한 대로 동작하며,
**4xx / 5xx / 타임아웃 / 기타 예외**에 대해 모두 **빈 리스트로 graceful fallback** 되도록 설계되었습니다.

---

### 5️⃣ Spring MVC + Thymeleaf UI

* `/search?q=키워드` 형태의 GET 요청으로 검색 수행
* `SearchResponseDto(answer, sources)`를 모델에 담아 Thymeleaf 템플릿(`search.html`)에 전달
* 화면에는:

    * LLM 답변
    * 출처 리스트(제목, URL, snippet)
      가 동시에 렌더링됩니다.
* `/` 요청 시 `/search`로 리다이렉트되도록 구성

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

### 🔎 파이프라인 요약 로그

`search()` 메서드 레벨에서 구간별 시간을 측정하고,
마지막에 한 줄 요약 로그를 남깁니다.

```text
Search pipeline summary. query='스프링', sources=3, 
braveMs=420, jsoupMs=780, llmMs=2100, totalMs=3405
```

이를 통해 “어디에서 시간이 많이 쓰였는지(검색/크롤링/LLM)”를 한 눈에 확인할 수 있습니다.

---

## 🧭 Environment & Logging

### 🔹 MDC 기반 traceId 전파

* 진입 필터/인터셉터에서 `MDC.put("traceId", ...)`로 traceId를 설정한다고 가정
* `SearchServiceImpl.callBraveSearch()`에서:

    * `String traceId = MDC.get("traceId");`
    * Brave API 호출 시 `X-Trace-Id` 헤더로 전달
* `logback-spring.xml` 패턴:

```text
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n
```

→ 모든 로그에 `[traceId]`를 포함시켜, 단일 요청을 추적할 수 있습니다.

---

### 🔹 Logback 공통 설정

`src/main/resources/logback-spring.xml`

* 공통 프로퍼티

    * `LOG_PATH = logs`
    * `LOG_PATTERN`에 날짜, 스레드, 레벨, traceId, 로거 이름, 메시지 포함
* **CONSOLE**

    * 개발자용 콘솔 출력
* **FILE (RollingFileAppender)**

    * `logs/app.log`
    * `SizeAndTimeBasedRollingPolicy`

        * 파일 이름: `logs/app-YYYY-MM-DD.i.log`
        * 파일당 최대 10MB
        * 최대 30일 보관
* **ERROR_FILE**

    * `logs/error.log`
    * `LevelFilter`로 ERROR 레벨만 기록
    * `logs/error-YYYY-MM-DD.i.log`로 롤링

---

### 🔹 dev 프로파일 (`spring.profiles.active=dev`)

```xml
<springProfile name="dev">
    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/app-json-dev.log</file>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"ai-search","env":"dev"}</customFields>
            <includeMdc>true</includeMdc>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/app-json-dev-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>3</maxHistory>
        </rollingPolicy>
    </appender>

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

* 개발 환경에서:

    * 전체 로그 레벨 `DEBUG`
    * WebClient / Reactor / 서비스 로그를 상세하게 확인 가능
    * JSON 로그(`app-json-dev.log`)로 ELK/Loki 테스트 수집 가능

---

### 🔹 prod 프로파일 (`spring.profiles.active=prod`)

```xml
<springProfile name="prod">
    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/app-json.log</file>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"ai-search","env":"prod"}</customFields>
            <includeMdc>true</includeMdc>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/app-json-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
        <appender-ref ref="ERROR_FILE"/>
        <appender-ref ref="JSON_FILE"/>
    </root>

    <logger name="org.thymeleaf" level="ERROR"/>
    <logger name="BRAVE_WEBCLIENT" level="INFO"/>
    <logger name="reactor.netty.http.client" level="WARN"/>
    <logger name="org.springframework.web.reactive.function.client" level="WARN"/>
    <logger name="com.example.ai_search" level="INFO"/>
</springProfile>
```

* 운영 환경에서:

    * 전체 로그 레벨 `INFO`
    * WebClient / Reactor는 `WARN` 이상만 출력
    * ERROR 전용 파일로 심각한 오류를 분리 관리
    * JSON 로그(`app-json.log`)로 ELK/Loki 수집 가능

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
│   ├── WebClientConfig.java       # Brave WebClient 설정
│   └── GeminiConfig.java          # Gemini Client 설정
├── dto/
│   ├── BraveSearchResponse.java
│   ├── SearchResponseDto.java
│   └── SourceDto.java
└── resources/
    ├── templates/
    │   └── search.html
    ├── application.properties.example
    ├── application-dev.yml
    ├── application-prod.yml
    └── logback-spring.xml
```

실제 `application.properties`는 `.gitignore` 처리하여 GitHub에 올라가지 않습니다.

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
llm.model=gemini-2.0-flash-lite
```

### 3) 운영/개발 환경에서는 OS 환경 변수 사용

**Windows (예시)**

```powershell
setx SEARCH_API_KEY "your_brave_key"
setx LLM_API_KEY "your_gemini_key"
```

**macOS / Linux (예시)**

```bash
export SEARCH_API_KEY=your_brave_key
export LLM_API_KEY=your_gemini_key
```

---

## 🧪 Testing & Resilience

외부 API 장애까지 고려해 아래와 같은 테스트를 작성했습니다.

### ✅ 1. 컨텍스트 로딩 테스트

```java
@SpringBootTest(properties = {
    "llm.api.key=dummy-llm-key",
    "llm.model=gemini-test-model",
    "search.api.key=dummy-search-key",
    "spring.profiles.active=dev"
})
class AiSearchApplicationTests {
    @Test
    void contextLoads() { }
}
```

* 테스트 환경에서만 dummy 프로퍼티를 주입해
  **환경변수 없이도 Spring 컨텍스트가 뜨도록 구성**했습니다.

### ✅ 2. `SearchControllerTest`

* `/search` 호출 시:

    * 상태 코드 200
    * 뷰 이름 `"search"`
    * `query`, `result` 모델 속성 검증
* `/` 호출 시:

    * `/search`로 리다이렉트되는지 검증

### ✅ 3. Brave Search fallback 테스트

* Brave가 **4xx**를 반환할 때:

    * `BraveClientException` → retry 없이 곧바로 fallback
    * 결과: `List<SourceDto>`가 비어 있는지 검증
* Brave가 **5xx**를 반환할 때:

    * retry(backoff) 수행 후, 최종적으로 fallback([])
    * WebClient 호출 횟수(최소 3회 이상)까지 검증

### ✅ 4. Jsoup fallback 테스트

* Jsoup의 `connect()`가 예외를 던지는 상황을 mock 처리
* `fetchPageTextsParallel()` 호출 시:

    * 결과 리스트 크기는 유지
    * 해당 URL의 내용이 `""` (빈 문자열)로 들어가는지 검증

### ✅ 5. Gemini LLM fallback 테스트

* `Client`/`Models` mock을 사용해 `generateContent()`가 항상 예외를 던지도록 설정
* `callLLM()` 호출 시:

    * 내부에서 2회 재시도 후
    * 최종적으로 **fallback 메시지**를 반환하는지 검증

> 위 테스트들을 통해
> **Brave / Jsoup / Gemini 중 어디가 장애가 나더라도,
> 서비스 전체가 죽지 않고 “가능한 수준의 답변/출처 정보”를 제공한다는 것**을 보장합니다.

---

## 🛠 How to Run (Local)

```bash
# 1. 빌드
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
http://localhost:8080/search
```

---

## 🐳 Docker & Deploy (Render 예시)

### Dockerfile (요약)

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew clean build

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
CMD ["java", "-jar", "app.jar"]
```

* CI(GitHub Actions)에서 `./gradlew clean build` 실행 후
* Render에서 Dockerfile 기반으로 빌드 & 배포
* Render 환경변수 설정:

    * `SEARCH_API_KEY`
    * `LLM_API_KEY`
    * `SPRING_PROFILES_ACTIVE=prod`

---

## 📈 Logging Example

```text
INFO  c.e.a.service.SearchServiceImpl - Search pipeline start. query='스프링부트'
INFO  c.e.a.service.SearchServiceImpl - Search requested. query='스프링부트'
DEBUG BRAVE_WEBCLIENT - [Reactor Netty HTTP 로그 ...]
DEBUG c.e.a.service.SearchServiceImpl - Brave DTO response for query='스프링부트', resultCount=3
INFO  c.e.a.service.SearchServiceImpl - Brave search done. query='스프링부트', resultCount=3
DEBUG c.e.a.service.SearchServiceImpl - Brave search success. elapsedMs=1417
DEBUG c.e.a.service.SearchServiceImpl - Jsoup fetch success. url='https://...', elapsedMs=544, textLen=2000
INFO  c.e.a.service.SearchServiceImpl - Gemini call start. attempt=1, query='스프링부트', model=...
INFO  c.e.a.service.SearchServiceImpl - Gemini call success. attempt=1, elapsedMs=2415, answerLength=428
INFO  c.e.a.service.SearchServiceImpl - Search pipeline summary. query='스프링부트', sources=3, braveMs=420, jsoupMs=780, llmMs=2100, totalMs=4729
```

---

## 📌 LICENSE

MIT License

```
