FROM centos:7

RUN curl https://bintray.com/sbt/rpm/rpm > /etc/yum.repos.d/bintray-sbt-rpm.repo
RUN yum -y install git sbt && yum clean all

EXPOSE 8080

COPY build.sbt /usr/firecloud-orchestration/build.sbt
COPY src /usr/firecloud-orchestration/src
COPY project /usr/firecloud-orchestration/project
COPY application.conf /usr/firecloud-orchestration/application.conf
COPY test.conf /usr/firecloud-orchestration/test.conf
COPY model.json /usr/firecloud-orchestration/model.json

ENV JAVA_HOME '/usr/lib/jvm/jre-1.8.0/'
WORKDIR /usr/firecloud-orchestration

RUN alternatives --set java /usr/lib/jvm/java-1.8.0-openjdk-1.8.0.51-1.b16.el7_1.x86_64/jre/bin/java

RUN sbt assembly -Dconfig.file=/usr/firecloud-orchestration/test.conf

CMD java -Dconfig.file=/usr/firecloud-orchestration/application.conf -jar $(ls target/scala-2.11/FireCloud-Orchestration-assembly-* | tail -n 1)
