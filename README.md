# AI Search – Spring Boot Mini Perplexity

간단한 검색 API + Gemini LLM 기반의 **출처 기반 답변 생성 서비스**

이 프로젝트는 Brave Search API로 웹 검색 결과를 가져오고,
Gemini LLM을 사용해 **출처를 기반으로 한 자연어 답변을 생성하는 Mini Perplexity 형태의 웹 서비스**입니다.

Spring Boot(Backend) + Thymeleaf(View) 기반으로 작동하며,
검색 → 본문 수집 → LLM 요약의 전체 파이프라인을 구현합니다.

---

## 🚀 Features (핵심 기능)

### ✔️ 1. 웹 검색 기능 (Brave Search API)

* Brave Search API를 사용해 상위 웹 검색 결과를 수집
* 제목, URL, description 반환
* 나중에 “AI-friendly snippet”, “alternate snippet”도 확장 가능

### ✔️ 2. Gemini LLM 기반 출처 기반 요약

* Brave 검색 결과를 LLM에 프롬프트로 전달
* Gemini 모델이 **출처 번호([1], [2])가 포함된 근거 기반 답변 생성**
* HTML 크롤링 대신 Brave의 snippet 사용 → 가볍고 빠름 (예정)

### ✔️ 3. 안전한 API Key 관리

* API Key는 GitHub에 포함되지 않도록
  `application.properties` → 환경변수 구조로 처리
* 깃허브에는 `application.properties.example`만 제공

### ✔️ 4. Spring MVC + Thymeleaf로 간단한 UI 제공

* `/search?q=keyword` 형태로 검색
* 답변 + 출처 리스트를 UI로 렌더링

---

## 🛠️ Tech Stack

### **Backend**

* Java 17
* Spring Boot 3.5.7
* Spring Web MVC
* Spring WebFlux (WebClient)
* Thymeleaf

### **AI & Infra**

* Brave Search API
* Google Gemini (Java SDK: `com.google.genai:google-genai`)
* Jsoup (선택 / HTML 텍스트 파싱용)

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
│   ├── SearchResponseDto.java
│   └── SourceDto.java
└── resources/
    ├── templates/search.html
    ├── application.properties         (gitignore)
    └── application.properties.example (GitHub 업로드용)
```

---

## ⚙️ How to Run (실행 방법)

### 1. Brave Search API 키 발급

[https://brave.com/search/api/](https://brave.com/search/api/)
무료 플랜(Data for AI) 사용 가능
→ 월 2,000 요청 무료

### 2. Gemini API 키 발급

[https://aistudio.google.com/](https://aistudio.google.com/)
→ Key 생성 후 환경변수에 저장

### 3. 환경 변수 설정 (중요)

프로젝트는 환경변수에서 키를 읽음.

#### Windows (PowerShell)

```powershell
setx SEARCH_API_KEY "your_brave_key"
setx LLM_API_KEY "your_gemini_key"
```

#### Linux / Mac

```bash
export SEARCH_API_KEY=your_brave_key
export LLM_API_KEY=your_gemini_key
```

### 4. application.properties.example 복사

```bash
cp src/main/resources/application.properties.example \
   src/main/resources/application.properties
```

### 5. Gradle 빌드 & 실행

```bash
./gradlew bootRun
```

### 6. 접속

```
http://localhost:8080/search
```

---

## 🔐 API Key Security

이 프로젝트는 API 키를 **절대 GitHub에 올리지 않도록** 구성돼 있다.

### ✔️ `.gitignore`에 추가됨:

```
src/main/resources/application.properties
.env
```

### ✔️ 깃허브에는 템플릿만 제공:

```
application.properties.example
```

실제 키는 환경변수로만 관리.

---

## 🔍 How It Works (동작 구조)

```
[Client Request]
     ↓
SearchController
     ↓
SearchService
     ↓
① callBraveSearch(query)
    → Brave Search API 호출
    → 제목, URL, description 수집

② callLLM(query, sources)
    → Gemini SDK 호출
    → 출처 기반 답변 생성

③ SearchResponseDto(answer, sources)

     ↓
Thymeleaf UI 렌더링
```

---

## 🧩 Example Prompt (LLM 입력)

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
