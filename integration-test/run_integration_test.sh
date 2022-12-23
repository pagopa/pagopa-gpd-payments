# example: sh ./run_integration_test.sh <local|dev|uat|prod>

export API_SUBSCRIPTION_KEY=$2
export APICONFIG_SUBSCRIPTION_KEY=$3
export GPD_SUBSCRIPTION_KEY=$4
export GPS_SUBSCRIPTION_KEY=$5
export DONATIONS_SUBSCRIPTION_KEY=$6
export IUVGENERATOR_SUBSCRIPTION_KEY=$7

# create containers
cd ../docker || exit
sh ./run_docker.sh "$1"

# run integration tests
cd ../integration-test/src || exit
yarn install
yarn test