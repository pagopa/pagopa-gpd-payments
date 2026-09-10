const {get} = require("../utility/axios_common");

// Host of the vertical service that GPD Payments now calls directly for
// spontaneous payments (replaces the old GPS organization/service registry).
const vertical_service_host = process.env.vertical_service_host;

function verticalServiceHealthCheck() {
    return get(vertical_service_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

module.exports = {
    verticalServiceHealthCheck
}
