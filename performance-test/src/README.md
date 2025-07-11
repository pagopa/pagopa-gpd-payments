# Load tests for GPD-Payments project

- [Load tests for GPD-Payments µ-service](#load-tests-for-gpd-payments-µ-service)

This is a set of [k6](https://k6.io) load tests related to the GPD-Payments, part of _Gestione Posizioni Debitorie_ initiative.

To invoke k6 load test passing parameter use -e (or --env) flag:

```
-e MY_VARIABLE=MY_VALUE
# example
-e API_SUBSCRIPTION_KEY=<your-secret>
```

## How to run 🚀

### Development environment example

Use this command to launch the load tests:

```
k6 run \
-e VARS=./environments/dev.environment.json \
-e TEST_TYPE=./test-types/smoke.json \
-e K6_OUT=influxdb=http://nginx:8086/${db_name} \
-e API_SUBSCRIPTION_KEY=${sub_key} \
-e APICONFIG_SUBSCRIPTION_KEY=${apiconfig_sub_key} \
-e GPD_SUBSCRIPTION_KEY=${gpd_sub_key} \
-e GPS_SUBSCRIPTION_KEY=${gps_sub_key} \
-e DONATIONS_SUBSCRIPTION_KEY=${donations_sub_key} \
-e SOAP_SUBSCRIPTION_KEY=${soap_sub_key} \
-e DEBT_POSITION_NUMBER=${debt_position_number} \
send_rt_V2_workflow.js
```