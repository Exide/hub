#!/usr/bin/env bash

[[ ! -z "$1" ]] && PREFIX=$1 || PREFIX="default"

set -euo pipefail errfail

echo "Cleaning up any old files."
rm -rf docker/hub
rm -rf docker/hub.properties
rm -rf docker/logback.xml

echo "Building the code into a tarball."
gradle clean compileJava distTar

echo "Uncompressing the tarball."
tar -xvf build/distributions/hub-*.tgz -C docker/

echo "Putting the hub code where the Dockerfile wants it."
mv docker/hub-*/ docker/hub

echo "Adding the config files matching: ${PREFIX}"
cp docker/configs/${PREFIX}* docker/
for file in docker/${PREFIX}*; do
  mv "$file" "${file/${PREFIX}-/}"
done

echo "Building the docker image."
docker build --tag hub:local docker

echo "----------"
echo ""
echo "Usage:"
echo "  docker run --name hub --publish 80:80 --publish 3333:3333 hub:local"
echo ""
