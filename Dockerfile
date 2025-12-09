# Etapa 1 — construir el JAR con Maven
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# Copiamos los archivos del proyecto
COPY pom.xml .
COPY src ./src

# Compilamos el proyecto (sin tests)
RUN mvn -q -e -DskipTests package


# Etapa 2 — imagen final, ligera
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# Copiamos el JAR generado desde la etapa de build
# Usamos *.jar por si el nombre cambia (fonda-0.0.1-SNAPSHOT.jar, etc.)
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
