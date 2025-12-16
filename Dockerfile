# ====== Etapa 1: Build con Maven ======
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /app

# Copiar pom.xml y descargar dependencias para acelerar builds
COPY pom.xml .
RUN mvn -q dependency:go-offline

# Copiar el código y compilar
COPY src ./src
RUN mvn clean package -DskipTests


# ====== Etapa 2: Imagen final ======
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copiar JAR desde la etapa anterior
COPY --from=build /app/target/*.jar fonda.jar

# Puerto donde corre el microservicio
EXPOSE 7071

# Ejecutar
ENTRYPOINT ["java", "-jar", "fonda.jar"]
