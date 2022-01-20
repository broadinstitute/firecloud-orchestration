#!/bin/bash
set -euox pipefail
IFS=$'\n\t'


run_with_ui=${1:-'false'}

docker run --rm -it -v "$PWD":/working broadinstitute/dsde-toolbox \
  render-templates.sh local "$(<~/.vault-token)"

if [ "$run_with_ui" = 'false' ]; then
  sed -i '' 's/"20080:80"/"80:80"/1' target/config/proxy-compose.yaml
  sed -i '' 's/"20443:443"/"443:443"/1' target/config/proxy-compose.yaml
  sed -i '' 's/basePath: \/service/basePath: \//1' src/main/resources/swagger/api-docs.yaml
fi
