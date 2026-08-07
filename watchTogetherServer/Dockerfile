FROM eclipse-temurin:17-jre
RUN groupadd --system yfuse && \
    useradd --system --gid yfuse --home-dir /nonexistent --shell /usr/sbin/nologin yfuse
WORKDIR /app
COPY build/distributions/watchTogetherServer.tar /tmp/watchTogetherServer.tar
RUN tar -xf /tmp/watchTogetherServer.tar --strip-components=1 -C /app && \
    rm /tmp/watchTogetherServer.tar && \
    chown -R yfuse:yfuse /app && \
    install -d -o yfuse -g yfuse -m 0700 /var/lib/yfuse
ENV PORT=8080 \
    HOST=0.0.0.0 \
    ACCOUNT_DB_PATH=/var/lib/yfuse/account.db
VOLUME ["/var/lib/yfuse"]
EXPOSE 8080
USER yfuse
ENTRYPOINT ["/app/bin/watchTogetherServer"]
