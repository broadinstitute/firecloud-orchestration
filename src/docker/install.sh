#!/bin/bash
# script to sbt build the orch jar

set -e
ORCH_DIR=$1
cd $ORCH_DIR

sbt compile
sbt test
sbt assembly

ORCH_JAR=$(find target | grep 'FireCloud-Orchestration.*\.jar')
mv $ORCH_JAR .
sbt clean
