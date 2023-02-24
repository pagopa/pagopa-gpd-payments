const {post, get, put, del} = require("./common");
const fs = require("fs");
const ip = require('ip');

const payments_host = process.env.payments_host;
const nodo_host = process.env.nodo_host;
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
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paDemandPaymentNotice',
            "Ocp-Apim-Subscription-Key": process.env.SOAP_PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function verifyPaymentNotice(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paVerifyPaymentNotice',
            "Ocp-Apim-Subscription-Key": process.env.SOAP_PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function getPayment(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paGetPayment',
            "Ocp-Apim-Subscription-Key": process.env.SOAP_PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function sendRT(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paSendRT',
            "Ocp-Apim-Subscription-Key": process.env.SOAP_PAYMENTS_SUBSCRIPTION_KEY
        }
    })
}

function activatePaymentNotice(body) {
    return post(nodo_host + "/node-for-psp/v1", body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'activatePaymentNotice',
        }
    })
}

function sendPaymentOutcome(body) {
    return post(nodo_host + "/node-for-psp/v1", body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'sendPaymentOutcome',
        }
    })
}

module.exports = {
    activatePaymentNotice,
    demandPaymentNotice,
    getPayment,
    healthCheck,
    sendPaymentOutcome,
    sendRT,
    verifyPaymentNotice,
}
