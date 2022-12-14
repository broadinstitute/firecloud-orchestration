#!/bin/bash
# script to sbt build the orch jar

set -e
ORCH_DIR=$1
cd $ORCH_DIR

sbt -batch -d clean reload update compile
sbt -batch test
sbt --mem 2048 -batch assembly

ORCH_JAR=$(find target | grep 'FireCloud-Orchestration.*\.jar')
mv $ORCH_JAR .
sbt clean
