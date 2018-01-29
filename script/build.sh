#!/usr/bin/env bash

# Single source of truth for building Firecloud-Orchestration.
# @ Jackie Roberti
#
# Provide command line options to do one or several things:
#   jar : build orch jar
#   -d | --docker : provide arg either "build" or "push", to build and push docker image
# Jenkins build job should run with all options, for example,
#   ./script/build.sh jar -d push

set -ex
PROJECT=firecloud-orchestration

function make_jar()
{
    # Set for versioning the jar
    GIT_MODEL_HASH=$(git rev-parse --short origin/${BRANCH})

    docker run --rm -e GIT_MODEL_HASH=${GIT_MODEL_HASH} \
        -v $PWD:/working -w /working -v jar-cache:/root/.ivy -v jar-cache:/root/.ivy2 \
        broadinstitute/scala-baseimage /working/src/docker/install.sh /working
}

function docker_cmd()
{
    if [ $DOCKER_CMD = "build" ] || [ $DOCKER_CMD = "push" ]; then
        echo "building docker image..."
        if [ "$ENV" != "dev" ] && [ "$ENV" != "alpha" ] && [ "$ENV" != "staging" ] && [ "$ENV" != "perf" ]; then
            DOCKER_TAG=${BRANCH}
        else
            GIT_SHA=$(git rev-parse origin/${BRANCH})
            echo GIT_SHA=$GIT_SHA > env.properties
            DOCKER_TAG=${GIT_SHA:0:12}
        fi
        docker build -t $REPO:${DOCKER_TAG} .

        if [ $DOCKER_CMD="push" ]; then
            echo "pushing docker image..."
            docker push $REPO:${DOCKER_TAG}
        fi
    else
        echo "Not a valid docker option!  Choose either build or push (which includes build)"
    fi
}

BRANCH=${BRANCH:-$(git rev-parse --abbrev-ref HEAD)}  # default to current branch
REPO=${REPO:-broadinstitute/$PROJECT}
ENV=${ENV:-""}  # if env is not set, push an image with branch name

while [ "$1" != "" ]; do
    case $1 in
        jar) make_jar ;;
        -d | --docker) shift
                       echo $1
                       DOCKER_CMD=$1
                       docker_cmd
                       ;;
    esac
    shift
done
