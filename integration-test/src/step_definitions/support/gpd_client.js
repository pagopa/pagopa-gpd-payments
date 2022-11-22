const {get} = require("./common");
const fs = require("fs");

let rawdata = fs.readFileSync('./config/properties.json');
let properties = JSON.parse(rawdata);
const gpd_host = properties.gpd_host;

function gpdHealthCheck() {
    return get(gpd_host + `/info`, {
        headers: {
            /* "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY */
            "Ocp-Apim-Subscription-Key": properties.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    gpdHealthCheck
}