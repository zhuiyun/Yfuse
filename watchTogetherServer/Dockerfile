FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/install/watchTogetherServer/ /app/
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/watchTogetherServer"]
