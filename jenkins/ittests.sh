#!/bin/bash

set -eux

# start elasticsearch and pre-populate test data
./docker/run-es.sh start

# execute tests, overriding elasticsearch.urls to point at the linked container
SBT_IMAGE=hseeberger/scala-sbt:11.0.9.1_1.4.6_2.12.12
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