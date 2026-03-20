# Build: mvn -q -DskipTests package && docker build -t archimo-sample-ecommerce:local .
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/archimo-sample-ecommerce-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
