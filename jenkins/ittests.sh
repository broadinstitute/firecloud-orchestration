#!/bin/bash

set -eux

# start elasticsearch and pre-populate test data
./docker/run-es.sh start

# execute tests, overriding elasticsearch.urls to point at the linked container
SBT_IMAGE=broadinstitute/scala-baseimage:jdk11-2.12.12-1.4.9
docker run --rm \
  --link elasticsearch-ittest:elasticsearch-ittest \
  -v sbt-cache:/root/.sbt \
  -v jar-cache:/root/.ivy2 \
  -v coursier-cache:/root/.cache/coursier \
  -v $PWD:/working \
  -w /working \
  $SBT_IMAGE sbt it:test -Delasticsearch.urls=elasticsearch-ittest:9300

# stop elasticsearch
./docker/run-es.sh stop
