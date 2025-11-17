# AI Search – Spring Boot Mini Perplexity

간단한 검색 API + Gemini LLM 기반의 **출처 기반 답변 생성 서비스**

이 프로젝트는 Brave Search API로 웹 검색 결과를 가져오고,
Google Gemini LLM으로 **출처가 포함된 근거 기반 답변**을 생성하는
Mini Perplexity 형태의 웹 서비스입니다.

Spring Boot(Backend) + Thymeleaf(View) 기반으로 작동하며,
**검색 → 본문 수집 → LLM 요약**의 전체 파이프라인을 구현합니다.

또한, 외부 API 특성에 맞추어
**timeout / retry / fallback / structured logging**을 적용해
견고한 파이프라인을 유지하도록 설계했습니다.

---

## 🚀 Features (핵심 기능)

### ✔️ 1. Brave Search API 기반 웹 검색

* Brave Search API를 통해 상위 검색 결과 수집
* 제목, URL, description(snippet) 사용
* 향후 *AI-friendly snippet*, *alternate snippet* 확장 가능

### ✔️ 2. Gemini LLM 기반 출처 기반 요약

* Brave 검색 결과를 기반으로 Gemini에 프롬프트 구성
* Gemini가 **출처 번호([1], [2])로 근거를 명시한 답변 생성**
* 필요 시 Jsoup로 본문 일부를 크롤링하여 LLM 입력 강화

### ✔️ 3. API Key 완전 격리 보안 구조

* `application.properties`는 GitHub에 업로드하지 않음
* `application.properties.example`만 제공
* 실제 키는 환경변수로만 관리

### ✔️ 4. Spring MVC + Thymeleaf UI 제공

* `/search?q=keyword` 형태로 간단하게 검색 가능
* 답변 + 출처 리스트 시각적으로 출력

---

## ⚙️ Reliability (안정성 설계)

외부 API(Brave / Gemini)를 사용하는 만큼,
해당 서비스는 다음과 같은 **장애 대비 메커니즘**을 포함합니다:

### 🔧 1) Brave Search API

* WebClient 기반 **connect/read timeout**
* 서버 오류(5xx) 시 **retry + backoff**
* 4xx 오류는 retry 불가 → 즉시 fallback
* 최종 실패 시 **빈 리스트 반환(fallback)**

### 🔧 2) Jsoup HTML Fetching

* 페이지 파싱 timeout 적용
* substring 안전 처리(2000자 제한)
* 실패 시 WARN 로그 + 빈 문자열로 fallback

### 🔧 3) Gemini LLM 호출

* LLM 요청을 논리적 타임아웃(CompletableFuture + timeout)으로 감싸 처리
* 최대 3회 retry(지수 backoff: 0.3s → 0.6s → 1.2s)
* 모든 시도 실패 시 **친절한 fallback 메시지** 반환

### 🔧 4) Structured Logging

* INFO: 파이프라인 시작/종료, Brave/Gemini 요약 정보
* WARN: 외부 API 오류, timeout, fallback 발동
* DEBUG: Brave/Jsoup/Gemini 상세 로깅(개발 환경에서만 활성화)
* 전체 파이프라인 소요시간(ms) 기록

---

## 🔍 Search Pipeline (전체 흐름)

```
[Client Request]
      ↓
SearchController
      ↓
SearchService.search()
      ↓
① callBraveSearch(query)
   - WebClient 호출
   - timeout / retry(backoff) / fallback
   - 검색 결과 DTO 변환

② fetchPageText(url)
   - Jsoup 텍스트 파싱
   - timeout / substring 안전 처리
   - 실패 시 빈 문자열

③ callLLM(query, sources, contents)
   - Gemini SDK 호출
   - 논리 타임아웃 / retry(backoff)
   - 실패 시 사용자 friendly fallback 답변

④ SearchResponseDto(answer, sources)
      ↓
Thymeleaf UI 렌더링
```

---

## 🧩 Example Prompt

```text
너는 '웹 출처 기반 답변 AI'이다.
아래 출처들만 근거로 답변하고,
문장 끝에 [1], [2] 같은 번호로 출처를 표시한다.
확실하지 않은 내용은 '확실하지 않음'이라고 표시한다.

질문: {query}

출처들:
[1] 제목: ...
    URL: ...
    내용 일부: ...

[2] 제목: ...
    ...
```

---

## 🛠️ Tech Stack

### **Backend**

* Java 21
* Spring Boot 3.5.7
* Spring MVC
* Spring WebFlux (WebClient)
* Thymeleaf

### **AI & Infra**

* Brave Search API
* Google Gemini (Java SDK: `com.google.genai:google-genai`)
* Jsoup — HTML 본문 크롤링

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
│   ├── WebClientConfig.java
│   └── GeminiConfig.java
├── dto/
│   ├── BraveSearchResponse.java
│   ├── SearchRequest.java
│   ├── SearchResponseDto.java
│   └── SourceDto.java
└── resources/
    ├── templates/search.html
    ├── application.properties         (gitignore)
    └── application.properties.example (GitHub 업로드용)
    └── logback-spring.xml
```

---

## 🔐 API Key Security

### ✔️ `.gitignore`에 추가됨

```
src/main/resources/application.properties
.env
```

### ✔️ GitHub에는 템플릿만 제공

```
application.properties.example
```

### ✔️ 실제 API Key는 환경변수로만 관리

---

## ⚙️ How to Run

### 1. Brave Search API 키 발급

[https://brave.com/search/api/](https://brave.com/search/api/)

### 2. Gemini API 키 발급

[https://aistudio.google.com/](https://aistudio.google.com/)

### 3. 환경변수 등록

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

### 4. properties 파일 설정

```bash
cp src/main/resources/application.properties.example \
   src/main/resources/application.properties
```

### 5. 실행

```bash
./gradlew bootRun
```

### 6. 접속

```
http://localhost:8080/search
```

---

## 📈 Logging Example (실제 수행 로그 예시)

```
INFO  c.e.a.service.SearchServiceImpl - Search pipeline start. query='스프링부트'
INFO  c.e.a.service.SearchServiceImpl - Search requested. query='스프링부트'
DEBUG c.e.a.service.SearchServiceImpl - Brave DTO response for query='스프링부트', resultCount=3
INFO  c.e.a.service.SearchServiceImpl - Brave search done. query='스프링부트', resultCount=3
DEBUG c.e.a.service.SearchServiceImpl - Brave search success. elapsedMs=1417
DEBUG c.e.a.service.SearchServiceImpl - Jsoup fetch success. url='https://...', elapsedMs=544, textLen=2000
DEBUG c.e.a.service.SearchServiceImpl - Jsoup fetch success. url='https://...', elapsedMs=202, textLen=2000
DEBUG c.e.a.service.SearchServiceImpl - Jsoup fetch success. url='https://...', elapsedMs=128, textLen=2000
INFO  c.e.a.service.SearchServiceImpl - Gemini call start. attempt=1, query='스프링부트', model=gemini-2.0-flash-lite
INFO  c.e.a.service.SearchServiceImpl - Gemini call success. attempt=1, elapsedMs=2415, answerLength=428
INFO  c.e.a.service.SearchServiceImpl - Search pipeline done. query='스프링부트', totalMs=4729
```

---


## 📌 LICENSE

MIT License 

---