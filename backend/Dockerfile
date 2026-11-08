# syntax=docker/dockerfile:1
# The image of the OpenDevTrack back end, built from the sources (stack/README.md).
#
# authz-library is read from GitHub Packages: the build takes a Maven settings.xml with the server "authz-library"
# (backend/README.md) as the BuildKit secret "maven-settings"; it is never copied into a layer.
#   docker build --secret id=maven-settings,src=<settings.xml> -t opendevtrack-backend backend

FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=secret,id=maven-settings,target=/root/.m2/settings.xml,required=true \
    mvn -q -B package -DskipTests \
    && cp "$(ls target/opendevtrack-backend-*.jar | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)" /app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system odt && useradd --system --gid odt --home /app odt
WORKDIR /app
COPY --from=build /app.jar app.jar
USER odt
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
