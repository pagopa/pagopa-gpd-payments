const {get} = require("./common");
const fs = require("fs");

const iuv_generator_host = process.env.iuv_generator_host;

function iuvGenHealthCheck() {
    return get(iuv_generator_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.IUVGENERATOR_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    iuvGenHealthCheck
}