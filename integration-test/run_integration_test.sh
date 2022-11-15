# example: sh ./run_integration_test.sh <local|dev|uat|prod>

# create containers
cd ../docker || exit
sh ./run_docker.sh "$1"

# run integration tests
cd ../integration-test/src || exit
yarn install
yarn test
