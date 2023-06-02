#!/usr/bin/env bash

# We are running 5.4.0 on the live cluster
ELASTICSEARCH_VERSION=5.4.0
start() {


    echo "attempting to remove old $CONTAINER container..."
    docker rm -f $CONTAINER || echo "docker rm failed. nothing to rm."

    # start up elasticsearch
    # ES recommends allocating at least 4GB of RAM to docker, for this image to run.
    # We set ES's RAM to 512m via ES_JAVA_OPTS instead of its 2GB default, to reduce this need.
    # However, if you find this script fails silently, fails mysteriously, or fails while attempting
    # to pre-populate data, try increasing Docker's RAM allocation.
    # https://www.elastic.co/guide/en/elasticsearch/reference/5.4/heap-size.html
    echo "starting up elasticsearch container..."
    docker run --name $CONTAINER -p 9200:9200 -p 9300:9300 -d \
      -e "cluster.name=elasticsearch5a" \
      -e "xpack.security.enabled=false" \
      -e "discovery.type=single-node" \
      -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
      docker.elastic.co/elasticsearch/elasticsearch:$ELASTICSEARCH_VERSION

    # validate elasticsearch. we started it as a single node, so it can show its health as yellow, not green.
    echo "Checking every 3s for elasticsearch cluster health to report ok"
    sleep 3
    ESSTATUS="unknown"
    while [[ $ESSTATUS != "yellow" && $ESSTATUS != "green" ]]; do
      ESSTATUS="$(curl -s http://localhost:9200/_cat/health?format=json | jq -r .[0].status)"
      if [ -z "$ESSTATUS" ]; then
        ESSTATUS="unknown"
      fi
      echo "... current status $ESSTATUS"
      sleep 3
    done

    # prepopulate data via elasticdump: https://www.npmjs.com/package/elasticdump
    # add --quiet to these calls if they are too verbose
    echo "pre-populating ontology fixture data; this can take a few minutes ..."
    docker run --net=host --rm -v $PWD/docker/data:/tmp elasticdump/elasticsearch-dump:v6.100.0 \
      --input=/tmp/ontology_mapping_dump.json.gz \
      --output=http://localhost:9200/ontology-unittest \
      --fsCompress \
      --type=mapping

    # limit 100 is the default, but we list it explicitly here for clarity and to make it easy to change later
    docker run --net=host --rm -v $PWD/docker/data:/tmp elasticdump/elasticsearch-dump:v6.100.0 \
      --input=/tmp/ontology_data_dump.json.gz \
      --output=http://localhost:9200/ontology-unittest \
      --fsCompress \
      --limit 100 \
      --type=data

}

stop() {
    echo "Stopping docker $CONTAINER container..."
    docker stop $CONTAINER || echo "elasticsearch stop failed. container already stopped."
    docker rm -v $CONTAINER || echo "elasticsearch rm -v failed.  container already destroyed."
}

CONTAINER=elasticsearch-ittest
COMMAND=$1

if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start"
    exit 1
fi

if [ $COMMAND = "start" ]; then
    start
elif [ $COMMAND = "stop" ]; then
    stop
else
    exit 1
fi
