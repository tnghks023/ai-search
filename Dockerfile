FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY . .

# Gradle 캐시 없이 빠르게
RUN ./gradlew clean build -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

# Render에서 제공하는 포트 환경 변수를 사용
EXPOSE 10000

ENTRYPOINT ["java", "-jar", "app.jar"]
