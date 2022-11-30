# Load tests for Payments project

- [Load tests for Payments project](#load-tests-for-payments-project)
  - [01. Payment workflow](#01-payment-workflow)
  - [02. CU Scenario Payment workflow](#02-cu-scenario-payment-workflow)
  - [03. DemandNotice](#03-demandnotice)

This is a set of [k6](https://k6.io) load tests related to the GPD (_Gestione Posizioni Debitorie_) initiative.

To invoke k6 load test passing parameter use -e (or --env) flag:

```
-e MY_VARIABLE=MY_VALUE
```

## 01. Payment workflow

### Description

The test consists of several API call to test the payment workflow:
 - Calling the service GPD
   1. create debt position without validity date
   2. publish the debt position
 - Calling the service Payments
   1. VerifyPayment
   2. ActivatePayment
   3. SendRT

### Payment workflow

For testing the workflow previously defined, execute the following commands with the needed parameters:
```
k6 run -e BASE_GPD_URL=https://api.dev.platform.pagopa.it/gpd/api/v1 -e BASE_PAYMENTS_URL=https://api.dev.platform.pagopa.it/gpd-payments/api/v1 -e ID_BROKER_PA=15376371009 -e ID_STATION=15376371009_01 -e ID_PA=77777777777 -e PAYMENTS_SUBKEY=insert_here_your_subkey performance-test/src/payments_workflow.js
```


## 02. CU Scenario Payment workflow

### Description

Given a CSV input file (see `example.csv` file), the test consists of several API call to test the payment workflow:
- Calling the service
  1. VerifyPayment
  2. ActivatePayment
  3. SendRT

### Payment workflow

For testing the workflow previously defined, execute the following commands with the needed parameters:
```
k6 run -e FILENAME=example.csv -e BASE_PAYMENTS_URL=https://api.dev.platform.pagopa.it/gpd-payments/api/v1 -e ID_BROKER_PA=15376371009 -e ID_STATION=15376371009_01 performance-test/src/payments_workflow.oneshot.js
```


## 03. DemandNotice

### Description

The test consist of some API call to test the payment workflow on demand payment notice

### Payment workflow

For testing the workflow previously defined, execute the following commands with the needed parameters:
```
k6 run --env VARS=dev.environment.json --env TEST_TYPE=./test-types/load.json performance-test/src/payments_demand_notice.js
```

In particular:
 - See `./test-type` folder for the different `TEST_TYPE` values from the defined folders
 - See `dev.environment.json` file to set your environment variables
