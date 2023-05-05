const {get} = require("../utility/axios_common");
const fs = require("fs");

const iuv_generator_host = process.env.iuv_generator_host;

function iuvGenHealthCheck() {
    return get(iuv_generator_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

module.exports = {
    iuvGenHealthCheck
}
