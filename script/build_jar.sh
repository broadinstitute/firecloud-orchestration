#!/bin/bash

# This script provides an entry point to assemble the Sam jar file.
# Used by the sam-build.yaml workflow in terra-github-workflows.
#
set -e

# Set for versioning the jar
GIT_MODEL_HASH=$(git log -n 1 --pretty=format:%h)

docker run --rm -e GIT_MODEL_HASH=${GIT_MODEL_HASH} \
  -v $PWD:/working \
  -v jar-cache:/root/.ivy -v jar-cache:/root/.ivy2 \
  -w /working \
  sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.10_7_1.10.2_2.13.15 /working/src/docker/clean_install.sh /working

EXIT_CODE=$?

if [ $EXIT_CODE != 0 ]; then
    echo "jar build exited with status $EXIT_CODE"
    exit $EXIT_CODE
fi
