# Integration Tests
đ Integration tests are in `integration-test/src/` folder. See there for more information. 

## How run on Docker đŗ

To run the integration tests on docker, you can run from this directory the script:


``` shell
sh ./run_integration_test.sh <local|dev|uat|prod> <api-subscription-key> <apiconfig_subkey> <gpd_subkey> <gps_spontaneouspayments_subkey> <gps_donations_subkey> <payments_subkey>
```


âšī¸ _Note_: for **PagoPa ACR** is **required** the login `az acr login -n <acr-name>`

If you use dev, uat or prod **you test the images on Azure ACR**

---
đģ If you want to test your local branch,
``` shell
sh ./run_integration_test.sh local <api-subscription-key> <apiconfig_subkey> <gpd_subkey> <gps_spontaneouspayments_subkey> <gps_donations_subkey> <payments_subkey>
```

