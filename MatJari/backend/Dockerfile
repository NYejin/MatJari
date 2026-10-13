# ---- Build stage ----
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY build.gradle ./
COPY src ./src
RUN gradle build -x test --no-daemon

# ---- Run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# 시간 기준은 KST. 앱은 Clock(Asia/Seoul, ClockConfig)으로 시각을 만들지만,
# LocalDateTime.now() 같은 JVM 기본 TZ 의존 코드가 섞여도 UTC로 저장되지 않도록 방어선을 둔다.
ENV TZ=Asia/Seoul
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
