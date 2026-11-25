FROM maven:3.10.0-eclipse-temurin-25-noble@sha256:e3760e0213ee8274e8b7926e5ecfcbabcbef93414c5561c4ecb657e435865329 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25.0.5.1_2-jre-noble@sha256:2ec98aa522c472deadfd2b7c5b39bca680e33ef522a53a72f10789bdf89a6bb5
WORKDIR /app
COPY --from=build /workspace/target/shipment-milestone-reconciliation.jar service.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
