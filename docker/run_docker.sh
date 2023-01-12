# sh ./run_docker.sh --test <local|dev|uat|prod>

# number of arguments checking
if [ -z "$2" ]; then
  ENV=$1
else
  OPTION=$1
  ENV=$2
fi


if [ -z "$ENV" ]
then
  ENV="local"
  echo "No environment specified: local is used."
fi


if [ "$ENV" = "local" ]; then
  containerRegistry="pagopadcommonacr.azurecr.io"
  image="service-local:latest"
  echo "Running local image and dev dependencies"
else

  if [ "$ENV" = "dev" ]; then
    containerRegistry="pagopadcommonacr.azurecr.io"
    echo "Running all dev images"
  elif [ "$ENV" = "uat" ]; then
    containerRegistry="pagopaucommonacr.azurecr.io"
    echo "Running all uat images"
  elif [ "$ENV" = "prod" ]; then
    containerRegistry="pagopapcommonacr.azurecr.io"
    echo "Running all prod images"
  else
    echo "Error with parameter: use <local|dev|uat|prod>"
    exit 1
  fi

  pip3 install yq
  repository=$(yq -r '."microservice-chart".image.repository' ../helm/values-$ENV.yaml)
  image="${repository}:latest"
fi


export containerRegistry=${containerRegistry}
export image=${image}

stack_name=$(cd .. && basename "$PWD")
docker compose -p "${stack_name}" up app -d --remove-orphans --force-recreate
docker compose -p "${stack_name}" up azure-storage -d --remove-orphans --force-recreate

if [ "$OPTION" = "--test" ]; then
  docker compose -p "${stack_name}" up node-container -d --remove-orphans --force-recreate
fi

# waiting the containers
printf 'Waiting for the service'
attempt_counter=0
max_attempts=50
until $(curl --output /dev/null --silent --head --fail http://localhost:8080/info); do
    if [ ${attempt_counter} -eq ${max_attempts} ];then
      echo "Max attempts reached"
      exit 1
    fi

    printf '.'
    attempt_counter=$((attempt_counter+1))
    sleep 5
done
echo 'Service Started'
