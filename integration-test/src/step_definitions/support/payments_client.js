const {post, get, put, del} = require("./common");
const fs = require("fs");
const ip = require('ip');

const payments_host = process.env.payments_host;
const payments_info = process.env.payments_info;
const ipAddress = ip.address();

function healthCheck() {
    return get(payments_info, {
        headers: {
            "X-Forwarded-For": ipAddress,
            "Ocp-Apim-Subscription-Key": process.env.REST_PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function demandPaymentNotice(body) {
    return post(payments_host, body, {
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paDemandPaymentNotice',
            "Ocp-Apim-Subscription-Key": process.env.SOAP_PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

module.exports = {
    healthCheck,
    demandPaymentNotice
}
