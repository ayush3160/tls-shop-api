# Runtime image. The jar is built on the host (`mvn package`) using the already
# cached dependencies, then copied in — keeps the image build offline & fast.
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY target/tls-shop-api.jar /app/app.jar

EXPOSE 8080
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
