# 1단계: 빌드
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# 프로젝트 전체 복사
COPY . .

# gradlew 실행 권한 부여 (Permission denied 해결 포인트)
RUN chmod +x ./gradlew

# 테스트는 빼고 빌드
# RUN ./gradlew clean build -x test
RUN ./gradlew clean build

# 2단계: 실행용 이미지
FROM eclipse-temurin:21-jre

WORKDIR /app

# 빌드 결과 JAR 복사
COPY --from=build /app/build/libs/*.jar app.jar

# Render가 PORT 환경변수로 포트를 내려줌 → 그걸로 서버 띄우기
EXPOSE 10000

# Spring Boot를 $PORT로 띄우도록 설정
ENTRYPOINT ["sh", "-c", "java -Dserver.port=$PORT -jar app.jar"]
