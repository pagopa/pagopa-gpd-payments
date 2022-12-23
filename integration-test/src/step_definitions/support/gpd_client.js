const {get} = require("./common");
const fs = require("fs");

const gpd_host = process.env.gpd_host;

function gpdHealthCheck() {
    return get(gpd_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.GPD_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    gpdHealthCheck
}