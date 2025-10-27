# Dockerfile

# --- 1단계: 프로젝트 빌드 (JDK 17 사용) ---
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /app

# 빌드에 필요한 파일만 먼저 복사
COPY build.gradle settings.gradle /app/
COPY gradle /app/gradle/

# 의존성 먼저 다운로드
RUN gradle dependencies

# 전체 소스 코드 복사
COPY src /app/src/

# Gradle을 사용해 프로젝트 빌드 (테스트는 생략)
RUN gradle bootJar -x test

# --- 2단계: 실제 실행 (JRE 17 사용) ---
# 더 가벼운 JRE(Java 실행 환경) 이미지 사용
FROM openjdk:17-jre-slim
WORKDIR /app

# 1단계(build)에서 생성된 .jar 파일을 복사
COPY --from=build /app/build/libs/*.jar app.jar

# 컨테이너가 시작될 때 이 명령어를 실행
ENTRYPOINT ["java", "-jar", "app.jar"]