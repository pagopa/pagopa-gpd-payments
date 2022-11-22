const {get} = require("./common");
const fs = require("fs");

let rawdata = fs.readFileSync('./config/properties.json');
let properties = JSON.parse(rawdata);
const api_config_host = properties.api_config_host;

function apiConfigHealthCheck() {    
    return get(api_config_host + `/info`, {
        headers: {
            /* "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY */
            "Ocp-Apim-Subscription-Key": properties.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    apiConfigHealthCheck
}