#!/usr/bin/env bash

HELP_TEXT="$(cat <<EOF
 Build jar and docker images.
   jar: build jar
   -d | --docker : (default: no action) provide either "build" or "push" to
           build or push a docker image.  "push" will also perform build.
   -g | --gcr-registry: If this flag is set, will push to the specified GCR repository.
   -k | --service-account-key-file: (optional) path to a service account key json
           file. If set, the script will call "gcloud auth activate-service-account".
           Otherwise, the script will not authenticate with gcloud.
   -h | --help: print help text.
 Examples:
   Jenkins build job should run with all options, for example,
     ./docker/build.sh compile -d push
   To build the compile, the image, and push it to a gcr repository.
     ./docker/build.sh compile -d build -r gcr --project "my-awesome-project"
\t
EOF
)"

# Enable strict evaluation semantics
set -e

# Set default variables
DOCKER_CMD=
BRANCH=${BRANCH:-$(git rev-parse --abbrev-ref HEAD)}  # default to current branch
DOCKERHUB_REGISTRY=${DOCKERHUB_REGISTRY:-broadinstitute/$PROJECT}
DOCKERHUB_TESTS_REGISTRY=${DOCKERHUB_REGISTRY}-tests
GCR_REGISTRY=""
ENV=${ENV:-""}
SERVICE_ACCT_KEY_FILE=""

MAKE_JAR=false
RUN_DOCKER=false
PRINT_HELP=false

if [ -z "$1" ]; then
    echo "No argument supplied!"
    echo "run '${0} -h' to see available arguments."
    exit 1
fi
while [ "$1" != "" ]; do
    case $1 in
        jar)
            MAKE_JAR=true
            ;;
        -d | --docker)
            shift
            echo "docker command = $1"
            DOCKER_CMD=$1
            RUN_DOCKER=true
            ;;
        -g | --gcr-registry)
            shift
            echo "gcr registry = $1"
            GCR_REGISTRY=$1
            ;;
        -k | --service-account-key-file)
            shift
            echo "service-account-key-file = $1"
            SERVICE_ACCT_KEY_FILE=$1
            ;;
        -h | --help)
            PRINT_HELP=true
            ;;
        *)
            echo "Urecognized argument '${1}'."
            echo "run '${0} -h' to see available arguments."
            exit 1
            ;;

    esac
    shift
done

if $PRINT_HELP; then
    echo -e "${HELP_TEXT}"
    exit 0
fi

# Run gcloud auth if a service account key file was specified.
if [[ -n $SERVICE_ACCT_KEY_FILE ]]; then
  TMP_DIR=$(mktemp -d tmp-XXXXXX)
  export CLOUDSDK_CONFIG=$(pwd)/${TMP_DIR}
  gcloud auth activate-service-account --key-file="${SERVICE_ACCT_KEY_FILE}"
fi

function make_jar()
{
    # Set for versioning the jar
    GIT_MODEL_HASH=$(git rev-parse --short origin/${BRANCH})

    docker run --rm -e GIT_MODEL_HASH=${GIT_MODEL_HASH} \
        -v $PWD:/working -w /working -v jar-cache:/root/.ivy -v jar-cache:/root/.ivy2 \
        hseeberger/scala-sbt:eclipse-temurin-17.0.2_1.6.2_2.13.8 /working/src/docker/install.sh /working
}

function docker_cmd()
{
    if [ $DOCKER_CMD = "build" ] || [ $DOCKER_CMD = "push" ]; then
        GIT_SHA=$(git rev-parse origin/${BRANCH})
        echo GIT_SHA=$GIT_SHA > env.properties
        HASH_TAG=${GIT_SHA:0:12}

        echo "building ${DOCKERHUB_REGISTRY}:${HASH_TAG}..."
        docker build --pull -t $DOCKERHUB_REGISTRY:${HASH_TAG} .

        echo "building ${DOCKERHUB_TESTS_REGISTRY}:${HASH_TAG}..."
        cd automation
        docker build -f Dockerfile-tests -t $DOCKERHUB_TESTS_REGISTRY:${HASH_TAG} .
        cd ..

        if [ $DOCKER_CMD="push" ]; then
            echo "pushing ${DOCKERHUB_REGISTRY}:${HASH_TAG}..."
            docker push $DOCKERHUB_REGISTRY:${HASH_TAG}
            docker tag $DOCKERHUB_REGISTRY:${HASH_TAG} $DOCKERHUB_REGISTRY:${BRANCH}
            docker push $DOCKERHUB_REGISTRY:${BRANCH}

            echo "pushing ${DOCKERHUB_TESTS_REGISTRY}:${HASH_TAG}..."
            docker push $DOCKERHUB_TESTS_REGISTRY:${HASH_TAG}
            docker tag $DOCKERHUB_TESTS_REGISTRY:${HASH_TAG} $DOCKERHUB_TESTS_REGISTRY:${BRANCH}
            docker push $DOCKERHUB_TESTS_REGISTRY:${BRANCH}

            if [[ -n $GCR_REGISTRY ]]; then
                docker tag $DOCKERHUB_REGISTRY:${HASH_TAG} $GCR_REGISTRY:${HASH_TAG}
                gcloud docker -- push $GCR_REGISTRY:${HASH_TAG}
            fi
        fi
    else
        echo "Not a valid docker option!  Choose either build or push (which includes build)"
    fi
}

function cleanup()
{
    echo "cleaning up..."
    if [[ -n $SERVICE_ACCT_KEY_FILE ]]; then
      gcloud auth revoke && echo 'Token revoke succeeded' || echo 'Token revoke failed -- skipping'
      rm -rf ${CLOUDSDK_CONFIG}
    fi
}

if $MAKE_JAR; then
    make_jar
fi

if $RUN_DOCKER; then
    docker_cmd
fi

cleanup
