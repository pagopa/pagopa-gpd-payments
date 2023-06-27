const {post, get, put, del} = require("../utility/axios_common");
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
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function demandPaymentNotice(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paDemandPaymentNotice',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function verifyPaymentNotice(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paVerifyPaymentNotice',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function getPayment(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paGetPayment',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function getPaymentV2(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paGetPaymentV2',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function sendRT(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paSendRT',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function sendRTV2(body) {
    return post(payments_host, body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paSendRTV2',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    })
}

function activatePaymentNotice(body) {
    return post(nodo_host + "/node-for-psp/v1", body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'activatePaymentNotice'
        }
    })
}

function sendPaymentOutcome(body) {
    return post(nodo_host + "/node-for-psp/v1", body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'sendPaymentOutcome'
        }
    })
}

module.exports = {
    activatePaymentNotice,
    demandPaymentNotice,
    getPayment,
    getPaymentV2,
    healthCheck,
    sendPaymentOutcome,
    sendRT,
    sendRTV2,
    verifyPaymentNotice,
}
