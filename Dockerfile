FROM centos:7

RUN curl https://bintray.com/sbt/rpm/rpm > /etc/yum.repos.d/bintray-sbt-rpm.repo
RUN yum -y install git java-1.8.0-openjdk sbt supervisord && yum clean all

EXPOSE 8080

COPY build.sbt /usr/firecloud-orchestration/build.sbt
COPY src /usr/firecloud-orchestration/src
COPY project /usr/firecloud-orchestration/project
COPY application.conf /usr/firecloud-orchestration/application.conf

WORKDIR /usr/firecloud-orchestration

RUN sbt assembly -Dconfig.file=/usr/firecloud-orchestration/application.conf
