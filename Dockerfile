FROM maven:3.8.4-openjdk-11 AS MVN_BUILD

WORKDIR /opt/solo/
ADD . /tmp
RUN cd /tmp && mvn package -DskipTests -Pci -q && mv target/solo/* /opt/solo/ \
    && cp -f /tmp/src/main/resources/docker/* /opt/solo/

FROM eclipse-temurin:11-jre-alpine
LABEL maintainer="Liang Ding<845765@qq.com>"

RUN apk add --no-cache ca-certificates tzdata curl \
    && addgroup -S solo && adduser -S solo -G solo

WORKDIR /opt/solo/
COPY --from=MVN_BUILD /opt/solo/ /opt/solo/
RUN chown -R solo:solo /opt/solo/

ENV TZ=Asia/Shanghai
ARG git_commit=0
ENV git_commit=$git_commit

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=10s \
    CMD curl -f http://localhost:8080/health || exit 1

USER solo

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp 'lib/*:.' org.b3log.solo.Server \"$@\"", "--"]
