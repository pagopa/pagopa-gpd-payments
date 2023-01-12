# example: sh ./run_integration_test.sh <local|dev|uat|prod>

export APICONFIG_SUBSCRIPTION_KEY=$2
export GPD_SUBSCRIPTION_KEY=$3
export GPS_SUBSCRIPTION_KEY=$4
export DONATIONS_SUBSCRIPTION_KEY=$5
export IUVGENERATOR_SUBSCRIPTION_KEY=$6
export REST_PAYMENTS_SUBSCRIPTION_KEY=$7
export SOAP_PAYMENTS_SUBSCRIPTION_KEY=$8

ENV=$1

if [ "$ENV" = "local" ]; then
  # create containers
  cd ../docker || exit
  sh ./run_docker.sh --test "$ENV"
else
  containerRegistry=pagopa${ENV:0:1}commonacr.azurecr.io
  docker pull ${containerRegistry}/yarn-testing-base:latest
  docker run -dit --name node-container ${containerRegistry}/yarn-testing-base:latest
  test_type=:$ENV
fi

# run integration tests
cd ../integration-test || exit

docker cp -a ./src/. node-container:/test
docker exec -i node-container /bin/bash -c " \
cd ./test
export APICONFIG_SUBSCRIPTION_KEY=$2 \
export GPD_SUBSCRIPTION_KEY=$3 \
export GPS_SUBSCRIPTION_KEY=$4 \
export DONATIONS_SUBSCRIPTION_KEY=$5 \
export REST_PAYMENTS_SUBSCRIPTION_KEY=$7 \
export SOAP_PAYMENTS_SUBSCRIPTION_KEY=$8 \
export IUVGENERATOR_SUBSCRIPTION_KEY=$6 && \
yarn test${test_type}"
