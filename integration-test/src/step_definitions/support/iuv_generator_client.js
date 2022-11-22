const {get} = require("./common");
const fs = require("fs");

let rawdata = fs.readFileSync('./config/properties.json');
let properties = JSON.parse(rawdata);
const iuv_generator_host = properties.iuv_generator_host;

function iuvGenHealthCheck() {
    return get(iuv_generator_host + `/info`, {
        headers: {
            /* "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY */
            "Ocp-Apim-Subscription-Key": properties.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    iuvGenHealthCheck
}