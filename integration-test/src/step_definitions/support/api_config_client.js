const {get} = require("./common");
const fs = require("fs");

const api_config_host = process.env.api_config_host;

function apiConfigHealthCheck() {    
    return get(api_config_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    apiConfigHealthCheck
}