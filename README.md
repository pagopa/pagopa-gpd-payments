# Payments

- [Payments](#payments)
  * [Api Documentation 📖](#api-documentation---)
  * [Technology Stack 📚](#technology-stack---)
  * [Start Project Locally 🚀](#start-project-locally---)
    + [Prerequisites](#prerequisites)
    + [Run docker container](#run-docker-container)
  * [Develop Locally 💻](#develop-locally---)
    + [Prerequisites](#prerequisites-1)
    + [Run the project](#run-the-project)
  * [Testing 🧪](#testing---)
    - [Unit testing](#unit-testing)
    - [Integration testing](#integration-testing)
    - [Load testing](#load-testing)
  * [Mainteiners 👨‍💻](#mainteiners------)
  
---
## Api Documentation 📖
See the [OpenApi 3 here.](https://editor.swagger.io/?url=https://raw.githubusercontent.com/pagopa/pagopa-gpd-payments/main/openapi/openapi.json)

---

## Technology Stack 📚

- Java 11
- Spring Boot
- Spring Web
- Feign Client

---  

## Start Project Locally 🚀

### Prerequisites

- docker
- a running GPD mock (see `mock` folder of this repository)

### Run docker container

Under main folder typing:

`docker build pagopagpdpayments`

or under docker folder typing:

`docker-compose up --build`

if you have a Mac with m1 processor and you want to start the project locally using the Docker Compose, you must update 
this instruction in the Dockerfile as follows `FROM --platform=linux/amd64 adoptopenjdk/openjdk16:alpine`


---

## Develop Locally 💻

### Prerequisites

- git
- maven
- jdk-11
- docker

### Run the project

Under main folder typing:

`mvn spring-boot:run -Dspring-boot.run.profiles=local`

---

## Testing 🧪

### Prerequisites

- maven
- [newman](https://www.npmjs.com/package/newman)
- [postman-to-k6](https://github.com/apideck-libraries/postman-to-k6)
- [k6](https://k6.io/)

### Unit testing

Under `payments` folder typing:

`mvn clean verify -DGPD_SUBSCRIPTION_KEY=secret -DGPS_SUBSCRIPTION_KEY=secret -DAPICONFIG_SUBSCRIPTION_KEY=secret -DGPD_HOST=host -DGPS_HOST=host -DAPI_CONFIG_HOST=host`

### Integration testing

Under `payments` folder typing:

```sh
 newman run api-test/GPD.postman_collection.json --environment=api-test/local.postman_environment.json 
```

> **NOTE**: suppose `Started Payments` on port `8080`

### Load testing

Under `payments` folder typing:

```sh
postman-to-k6 api-test/GPD.postman_collection.json --environment api-test/local.postman_environment.json -o ./k6-script.js
k6 run --vus 2 --duration 30s ./k6-script.js
```

> **NOTE**: suppose `Started Payments` on port `8085`

---

## Mainteiners 👨‍💻

See `CODEOWNERS` file



