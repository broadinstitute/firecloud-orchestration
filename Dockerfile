FROM openjdk:8

# Standard apt-get cleanup.
RUN apt-get -yq autoremove && \
    apt-get -yq clean && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /tmp/* && \
    rm -rf /var/tmp/*

EXPOSE 8080

RUN mkdir /orch
COPY ./FireCloud-Orchestration*.jar /orch

CMD java $JAVA_OPTS -jar $(find /orch -name 'FireCloud-Orchestration*.jar')