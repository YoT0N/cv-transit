FROM eclipse-temurin:17-jre
COPY target/cv-transit-*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]