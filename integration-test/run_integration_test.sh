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
cd ../integration-test || exit

docker stop node-container-test && docker rm node-container-test
docker run -dit --name node-container-test node
docker cp ./src node-container-test:/integration-test
docker exec -u 0 node-container-test /bin/bash -c "echo '34.141.48.9 yarnpkg.com' >> /etc/hosts"
docker exec -i node-container-test /bin/bash -c " \
echo 'Exporting needed parameters...'
cd ./integration-test
export APICONFIG_SUBSCRIPTION_KEY=$2 \
export GPD_SUBSCRIPTION_KEY=$3 \
export GPS_SUBSCRIPTION_KEY=$4 \
export DONATIONS_SUBSCRIPTION_KEY=$5 \
export REST_PAYMENTS_SUBSCRIPTION_KEY=$7 \
export SOAP_PAYMENTS_SUBSCRIPTION_KEY=$8 \
export IUVGENERATOR_SUBSCRIPTION_KEY=$6 && \
echo 'Starting yarn installation...' && \
yarn install --network-timeout 1000000 && \
echo 'Starting yarn testing...' && \
yarn test"
# docker stop node-container-test && docker rm node-container-test
