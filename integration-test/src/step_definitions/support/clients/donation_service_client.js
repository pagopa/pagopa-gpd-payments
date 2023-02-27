const {get} = require("../utility/axios_common");
const fs = require("fs");

const donation_service_host = process.env.donation_host;

function donationHealthCheck() {
    return get(donation_service_host + `/donations/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.DONATIONS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    donationHealthCheck
}