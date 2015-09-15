FROM phusion/baseimage

# How to install OpenJDK 8 from:
# http://ubuntuhandbook.org/index.php/2015/01/install-openjdk-8-ubuntu-14-04-12-04-lts/
RUN add-apt-repository ppa:openjdk-r/ppa

# How to install sbt on Linux from:
# http://www.scala-sbt.org/release/tutorial/Installing-sbt-on-Linux.html
RUN echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 642AC823

RUN apt-get update
RUN apt-get install -qy openjdk-8-jdk sbt

# Standard apt-get cleanup.
RUN apt-get -yq autoremove && \
    apt-get -yq clean && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /tmp/* && \
    rm -rf /var/tmp/*

# Actually download sbt
RUN sbt version

EXPOSE 8080

RUN mkdir /app
WORKDIR /app

# Grab dependencies.
COPY build.sbt build.sbt
COPY project project
RUN sbt compile

# Compile first to cache it.
COPY src src
RUN sbt compile

# RUN AGORA_URL_ROOT='http://localhost:8989' RAWLS_URL_ROOT='http://localhost:8990' sbt test

RUN sbt assembly

COPY src/docker/run.sh /etc/service/orch/run
