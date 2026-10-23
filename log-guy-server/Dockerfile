# log-guy server. GraalVM, because GraalJS only compiles scripts on a JVMCI enabled JDK, see wiki/Technologie.md.
# Build after "mvn package": docker build -t log-guy-server log-guy-server
FROM ghcr.io/graalvm/jdk-community:25i4-25.0.4.1.1

RUN useradd --system --uid 10001 --home-dir /var/lib/log-guy log-guy \
  && mkdir -p /etc/log-guy/scripts /var/lib/log-guy /opt/log-guy \
  && chown log-guy /var/lib/log-guy

COPY src/docker/log-guy /usr/local/bin/log-guy
COPY target/log-guy-server-*.jar /opt/log-guy/log-guy-server.jar

# The image sets en_US.UTF-8 without having it, so Java would print messages of the CLI as ASCII
ENV LANG=C.UTF-8
USER log-guy
VOLUME ["/etc/log-guy", "/var/lib/log-guy"]
EXPOSE 8080
ENTRYPOINT ["log-guy"]
CMD ["serve"]
