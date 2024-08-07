#!/bin/bash
# v1.0 zarsky@broad
hash fswatch 2>/dev/null || {
    echo >&2 "This script requires fswatch (https://github.com/emcrisostomo/fswatch), but it's not installed. On Darwin, just \"brew install fswatch\".  Aborting."; exit 1;
}

if [ -a ./.docker-rsync-local.pid ]; then
    echo "Looks like clean-up wasn't completed, doing it now..."
    docker rm -f orch-rsync-container orch-proxy orch-sbt
    docker network rm fc-orch
    pkill -P $(< "./.docker-rsync-local.pid")
    rm ./.docker-rsync-local.pid
    rm config/built-config.conf
fi

clean_up () {
    echo
    echo "Cleaning up after myself..."
    docker rm -f orch-rsync-container orch-proxy orch-sbt
    docker network rm fc-orch
    pkill -P $$
    rm ./.docker-rsync-local.pid
    rm config/built-config.conf
}
trap clean_up EXIT HUP INT QUIT PIPE TERM 0 20

echo "Creating shared volumes if they don't exist..."
docker volume create --name orch-shared-source
docker volume create --name sbt-cache
docker volume create --name jar-cache
docker volume create --name coursier-cache

make_config () {
    echo "include \"firecloud-orchestration.conf\"" > config/built-config.conf

    if [[ "$LOCAL_RAWLS" == 'true' ]]; then
        echo "Using local rawls ..."
        echo "include \"local-rawls.conf\"" >> config/built-config.conf
    fi

    if [[ "$LOCAL_AGORA" == 'true' ]]; then
        echo "Using local agora ..."
        echo "include \"local-agora.conf\"" >> config/built-config.conf
    fi

    if [[ "$LOCAL_THURLOE" == 'true' ]]; then
        echo "Using local thurloe ..."
        echo "include \"local-thurloe.conf\"" >> config/built-config.conf
    fi

    if [[ "$LOCAL_SAM" == 'true' ]]; then
        echo "Using local sam ..."
        echo "include \"local-sam.conf\"" >> config/built-config.conf
    fi

    if [[ "$FIAB" == 'true' ]]; then
        echo "Using FiaB rawls, agora, thurloe, and sam ..."
        echo "include \"fiab.conf\"" >> config/built-config.conf
    fi
}
echo "Building config..."
make_config

echo "Launching rsync container..."
docker run -d \
    --name orch-rsync-container \
    -v orch-shared-source:/working \
    -e DAEMON=docker \
    tjamet/rsync

run_rsync ()  {
    rsync --blocking-io -azl --delete -e "docker exec -i" . orch-rsync-container:working \
        --filter='+ /build.sbt' \
        --filter='+ /config/***' \
        --filter='- /project/project/target/***' \
        --filter='- /project/target/***' \
        --filter='+ /project/***' \
        --filter='+ /src/***' \
        --filter='+ /.git/***' \
        --filter='- *'
}
echo "Performing initial file sync..."
run_rsync
fswatch -o . | while read f; do run_rsync; done &
echo $$ > ./.docker-rsync-local.pid

start_server () {
    DOCKER_JAVA_OPTS="-Dconfig.file=/app/config/built-config.conf"
    if [[ "$FIAB" == 'true' ]]; then
        DOCKER_JAVA_OPTS="$DOCKER_JAVA_OPTS -Djsse.enableSNIExtension=false"
    fi

    docker network create fc-orch

    echo "Creating SBT docker container..."

    # note the special escaping of the ctmpl double-brackets
    DOCKERHOST=$(docker network inspect fc-orch -f='{{(index .IPAM.Config 0).Gateway}}')
    if [[ -z $DOCKERHOST ]]; then
        echo "IPAM.Config.Gateway not found.  The Docker daemon may need a reload."
        echo "See https://github.com/moby/moby/issues/26799 for details."
        exit 2
    fi

    docker create -it --name orch-sbt \
    -v orch-shared-source:/app -w /app \
    -v sbt-cache:/root/.sbt \
    -v jar-cache:/root/.ivy2 \
    -v coursier-cache:/root/.cache/coursier \
    --add-host local.broadinstitute.org:$DOCKERHOST \
    -p 5051:5051 \
    --network=fc-orch \
    -e JAVA_OPTS="$DOCKER_JAVA_OPTS" \
    sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.10_7_1.10.1_2.13.14 \
    bash -c "git config --global --add safe.directory /app && sbt \~reStart"

    docker cp config/firecloud-account.pem orch-sbt:/etc/firecloud-account.pem
    docker cp config/firecloud-account.json orch-sbt:/etc/firecloud-account.json
    docker cp config/rawls-account.pem orch-sbt:/etc/rawls-account.pem
    docker cp config/rawls-account.json orch-sbt:/etc/rawls-account.json

    echo "Creating proxy..."
    docker create --name orch-proxy \
    --restart "always" \
    --network=fc-orch \
    -p 10080:80 -p 10443:443 \
    -e PROXY_URL='http://orch-sbt:8080/' \
    -e PROXY_URL2='http://orch-sbt:8080/api' \
    -e PROXY_URL3='http://orch-sbt:8080/register' \
    -e APACHE_HTTPD_TIMEOUT='650' \
    -e APACHE_HTTPD_KEEPALIVE='On' \
    -e APACHE_HTTPD_KEEPALIVETIMEOUT='650' \
    -e APACHE_HTTPD_MAXKEEPALIVEREQUESTS='500' \
    -e APACHE_HTTPD_PROXYTIMEOUT='650' \
    -e CALLBACK_URI='https://local.broadinstitute.org/oauth2callback' \
    -e LOG_LEVEL='warn' \
    -e PROXY_PATH:='/' \
    -e PROXY_PATH2='/api' \
    -e SERVER_NAME='local.broadinstitute.org' \
    -e REMOTE_USER_CLAIM='sub' \
    -e FILTER2='AddOutputFilterByType DEFLATE application/json text/plain text/html application/javascript application/x-javascript' \
    us.gcr.io/broad-dsp-gcr-public/httpd-terra-proxy:v0.1.16

    docker cp config/server.crt orch-proxy:/etc/ssl/certs/server.crt
    docker cp config/server.key orch-proxy:/etc/ssl/private/server.key
    docker cp config/ca-bundle.crt orch-proxy:/etc/ssl/certs/server-ca-bundle.crt
    docker cp config/oauth2.conf orch-proxy:/etc/httpd/conf.d/oauth2.conf
    docker cp config/site.conf orch-proxy:/etc/httpd/conf.d/site.conf

    echo "Starting proxy..."
    docker start orch-proxy
    echo "Starting SBT..."
    docker start -ai orch-sbt
}
start_server
