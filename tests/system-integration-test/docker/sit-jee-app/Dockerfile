FROM payara/server-full:5.2021.7-jdk11

USER root

LABEL com.jfrog.artifactory.retention.maxCount="25"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl

USER payara

COPY sit-jee-app.war /sit-jee-app.war
COPY start-me-up.sh /start-me-up.sh

ENV PATH ${PATH}:/opt/payara/appserver/bin
RUN asadmin --user admin --passwordFile=/opt/payara/passwordFile start-domain && \
    asadmin --user admin --passwordFile=/opt/payara/passwordFile create-managed-scheduled-executor-service --corepoolsize=100 concurrent/joynrMessagingScheduledExecutor && \
    asadmin --user admin --passwordFile=/opt/payara/passwordFile set-log-levels io.joynr=FINER && \
    asadmin --user admin --passwordFile=/opt/payara/passwordFile set-log-attributes com.sun.enterprise.server.logging.GFFileHandler.multiLineMode=false && \
    asadmin --user admin --passwordFile=/opt/payara/passwordFile stop-domain || \
    true

ENTRYPOINT ["/start-me-up.sh"]
