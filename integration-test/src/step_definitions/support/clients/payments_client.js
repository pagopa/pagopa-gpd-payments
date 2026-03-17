const { post, get } = require("../utility/axios_common");
const ip = require("ip");

const ENABLE_DEBUG = process.env.ENABLE_DEBUG === "true";
const payments_host = process.env.payments_host;
const nodo_host = process.env.nodo_host;
const payments_info = process.env.payments_info;
const ipAddress = ip.address();

function logSoapRequest(operationName, url, body, config) {
    if (!ENABLE_DEBUG) {
        return;
    }

    console.log(`[payments_client] ${operationName} url:`, url);
    console.log(`[payments_client] ${operationName} headers:`, config?.headers);
    console.log(`[payments_client] ${operationName} timeout:`, config?.timeout);
    console.log(`[payments_client] ${operationName} body:`, body);
}

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
    const url = payments_host;
    const config = {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paVerifyPaymentNotice',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    };

    logSoapRequest("verifyPaymentNotice", url, body, config);

    return post(url, body, config);
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
    return post(nodo_host + "/nodo/node-for-psp/v1", body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml; charset=utf-8',
            'SOAPAction': '"activatePaymentNoticeV2"',
            "Ocp-Apim-Subscription-Key": `${process.env.SUBKEY};product=nodo`
        }
    });
}

function sendPaymentOutcome(body) {
    return post(nodo_host + "/nodo/node-for-psp/v1", body, {
        timeout: 10000,
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'sendPaymentOutcome',
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    });
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
