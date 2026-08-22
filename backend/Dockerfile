FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven && \
    mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN useradd --create-home --user-group kotanoba
USER kotanoba
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
