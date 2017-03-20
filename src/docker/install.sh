#!/bin/bash


set -e
ORCH_DIR=$1
cd $ORCH_DIR

sbt compile
sbt test
sbt assembly

ORCH_JAR=$(find target | grep 'FirecloudOrchestration.*\.jar')
mv $ORCH_JAR .
sbt clean


# TODO: integration tests - make these own script?
# sbt -Desurls=$ESURLS it:test
