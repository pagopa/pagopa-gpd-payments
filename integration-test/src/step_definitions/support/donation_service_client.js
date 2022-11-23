const {get} = require("./common");
const fs = require("fs");

const donation_service_host = process.env.donation_host;

function donationHealthCheck() {
    return get(donation_service_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    donationHealthCheck
}