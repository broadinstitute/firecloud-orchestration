FROM us.gcr.io/broad-dsp-gcr-public/base/jre:17-debian

EXPOSE 8080

RUN mkdir /orch
COPY ./FireCloud-Orchestration*.jar /orch

# 1. “Exec” form of CMD necessary to avoid “shell” form’s `sh` stripping 
#    environment variables with periods in them, often used in DSP for Lightbend 
#    config.
# 2. Handling $JAVA_OPTS is necessary as long as firecloud-develop or the app’s 
#    chart tries to set it. Apps that use devops’s foundation subchart don’t need 
#    to handle this.
# 3. The jar’s location and naming scheme in the filesystem is required by preflight 
#    liquibase migrations in some app charts. Apps that expose liveness endpoints 
#    may not need preflight liquibase migrations.
# We use the “exec” form with `bash` to accomplish all of the above.
CMD ["/bin/bash", "-c", "java $JAVA_OPTS -jar $(find /orch -name 'FireCloud-Orchestration*.jar')"]
