# --- build ---
FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
COPY tools/vendor/leaflet ./tools/vendor/leaflet
COPY data/invalid ./data/invalid
RUN mvn -q -B verify

# --- run ---
FROM eclipse-temurin:11-jre
WORKDIR /app
RUN mkdir -p /app/data/uploads
COPY --from=build /build/target/heat-routing-*.jar app.jar
ENV APP_STORAGE_DIR=/app/data/uploads \
    JAVA_OPTS="-Xmx8g -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
