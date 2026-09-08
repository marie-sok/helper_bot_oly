FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

COPY . .
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/target/reminder-telegram-bot-1.0.0.jar /app/oly.jar

# Render Free gives Oly a small CPU budget and may cold-start it after idle.
# Optimize for startup/low-memory latency instead of peak JIT throughput.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=10.0 -XX:MaxRAMPercentage=72.0 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/oly.jar"]
