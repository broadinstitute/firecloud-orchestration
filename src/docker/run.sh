#!/bin/bash
set -euox pipefail
IFS=$'\n\t'


if [ -e /app/target/scala-2.13/FireCloud-Orchestration-assembly-*.jar ]; then
  exec java $JAVA_OPTS -jar /app/target/scala-2.13/FireCloud-Orchestration-assembly-*.jar
else
  cd /app
  exec sbt '~ reStart'
fi
