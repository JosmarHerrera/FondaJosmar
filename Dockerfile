# ====== Etapa 1: Build con Maven ======
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

# compila sin tests
RUN mvn -q -DskipTests clean package


# ====== Etapa 2: Run ======
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/target/*.jar fonda.jar

# Puerto del microservicio
EXPOSE 7071

ENTRYPOINT ["java","-jar","fonda.jar"]
