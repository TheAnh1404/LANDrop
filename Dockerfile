FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY frontend/pom.xml frontend/pom.xml
COPY backend/src backend/src
RUN mvn -B -pl backend -am -DskipTests package \
    && mvn -B -pl backend dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/backend/target/dependency

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home landrop
COPY --from=build --chown=landrop:landrop /build/backend/target/landrop-0.1.0-SNAPSHOT.jar /app/landrop.jar
COPY --from=build --chown=landrop:landrop /build/backend/target/dependency /app/lib
USER landrop
EXPOSE 3000 3001
ENTRYPOINT ["java", "-cp", "/app/landrop.jar:/app/lib/*", "com.landrop.server.LandropServerMain"]
