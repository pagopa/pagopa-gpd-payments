const {post, get, put, del} = require("./common");
const fs = require("fs");

const payments_host = process.env.payments_host;
const payments_info = process.env.payments_info;

function healthCheck() {
    return get(payments_info, {
        headers: {
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
