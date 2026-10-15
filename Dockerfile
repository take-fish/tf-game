FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY target/tf-game.jar /app/app.jar
EXPOSE 8104
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
