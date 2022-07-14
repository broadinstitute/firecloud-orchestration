FROM us.gcr.io/broad-dsp-gcr-public/base/jre:17-debian

EXPOSE 8080

RUN mkdir /orch
COPY ./FireCloud-Orchestration*.jar /orch

CMD java $JAVA_OPTS -jar $(find /orch -name 'FireCloud-Orchestration*.jar')
