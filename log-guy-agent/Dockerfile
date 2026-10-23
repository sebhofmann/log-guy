# log-guy agent, runs next to an application with its log directory mounted read only.
# Build after "mvn package": docker build -t log-guy-agent log-guy-agent
# The container needs read access to the logs, e.g. --user 100500 for MIR.
FROM eclipse-temurin:25.0.4.1_1-jre-noble

RUN mkdir -p /opt/log-guy-agent /etc/log-guy-agent
COPY target/log-guy-agent-*.jar /opt/log-guy-agent/log-guy-agent.jar

USER 10001
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx64m", "-jar", "/opt/log-guy-agent/log-guy-agent.jar"]
CMD ["run"]
