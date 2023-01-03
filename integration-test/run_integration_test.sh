# example: sh ./run_integration_test.sh <local|dev|uat|prod>

export APICONFIG_SUBSCRIPTION_KEY=$2
export GPD_SUBSCRIPTION_KEY=$3
export GPS_SUBSCRIPTION_KEY=$4
export DONATIONS_SUBSCRIPTION_KEY=$5
export IUVGENERATOR_SUBSCRIPTION_KEY=$6
export REST_PAYMENTS_SUBSCRIPTION_KEY=$7
export SOAP_PAYMENTS_SUBSCRIPTION_KEY=$8

# create containers
cd ../docker || exit
sh ./run_docker.sh "$1"

# run integration tests
cd ../integration-test/src || exit
yarn install
yarn test