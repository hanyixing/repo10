FROM maven:3.8.4-openjdk-11 as MVN_BUILD

WORKDIR /opt/solo/
ADD . /tmp
RUN cd /tmp && mvn package -DskipTests -Pci -q && mv target/solo/* /opt/solo/ \
&& cp -f /tmp/src/main/resources/docker/* /opt/solo/

FROM openjdk:18-slim
LABEL maintainer="Liang Ding<845765@qq.com>"

WORKDIR /opt/solo/
COPY --from=MVN_BUILD /opt/solo/ /opt/solo/
# curl is required by the HEALTHCHECK below
RUN apt-get update && apt-get install -y ca-certificates tzdata curl \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Shanghai
ARG git_commit=0
ENV git_commit=$git_commit

# Resource limits (JVM level). Override at runtime, e.g. -e JAVA_OPTS="-Xms512m -Xmx1g".
# Container-level cpu/memory limits are configured in docker-compose.yml / deploy.sh.
ENV JAVA_OPTS="-Xms256m -Xmx512m"
# Port used by the in-container HEALTHCHECK; keep in sync with --listen_port.
ENV SERVER_PORT=8080

EXPOSE 8080

# Probe a lightweight endpoint that returns 200 whenever the HTTP server is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/manifest.json" || exit 1

# sh -c lets us expand $JAVA_OPTS while still forwarding runtime args
# (e.g. --listen_port, --server_host) through "$@".
ENTRYPOINT [ "sh", "-c", "exec java $JAVA_OPTS -cp \"lib/*:.\" org.b3log.solo.Server \"$@\"", "--" ]
