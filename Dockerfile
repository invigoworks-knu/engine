FROM gradle:8.14.3-jdk17 AS build
WORKDIR /app

COPY build.gradle settings.gradle /app/
COPY gradle /app/gradle/

RUN gradle dependencies

COPY src /app/src/

RUN gradle bootJar -x test

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN apt-get update && \
    apt-get install -y ca-certificates-java && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]