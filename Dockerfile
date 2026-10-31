# One image serves both the API and the web interface, so the browser sees a single origin and CORS never comes up.
FROM node:24-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
# Tests already ran in CI, and they need an embedded database that this build stage has no use for.
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home kasa
USER kasa
COPY --from=backend /app/backend/target/kasa-backend-*.jar /app/kasa.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/kasa.jar"]
