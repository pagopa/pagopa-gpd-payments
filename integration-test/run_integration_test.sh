#!/bin/bash

# example: sh ./run_integration_test.sh <local|dev|uat|prod>
set -e

# create containers
cd ../docker || exit
chmod +x ./run_docker.sh
./run_docker.sh "$1"

# run integration tests
cd ../integration-test/src || exit
yarn install
yarn test:"$1"
