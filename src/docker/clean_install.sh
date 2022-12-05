#!/bin/bash

# This script runs sbt assembly to produce a target jar file.
# Used by build_jar.sh
# chmod +x must be set for this script
set -eux

ORCH_DIR=$1
cd $ORCH_DIR

export SBT_OPTS="-Xms5g -Xmx5g -XX:MaxMetaspaceSize=5g"
echo "starting sbt clean assembly ..."
sbt 'set assembly / test := {}' clean assembly
echo "... clean assembly complete, finding and moving jar ..."
ORCH_JAR=$(find target | grep 'FireCloud-Orchestration.*\.jar')
mv $ORCH_JAR .
echo "... jar moved."
