const { post, get } = require("../utility/axios_common");
const ip = require("ip");

const ENABLE_DEBUG = process.env.ENABLE_DEBUG === "true";
const SHOW_PARTIAL_SUBKEY_IN_DEBUG = process.env.SHOW_PARTIAL_SUBKEY_IN_DEBUG === "true";
const payments_host = process.env.payments_host;
const nodo_host = process.env.nodo_host;
const payments_info = process.env.payments_info;
const ipAddress = ip.address();

function maskSubscriptionKey(value) {
    if (!value) {
        return value;
    }

    if (!SHOW_PARTIAL_SUBKEY_IN_DEBUG) {
        return "***MASKED***";
    }

    const key = String(value).trim();
    return key.length > 8
        ? `${key.slice(0, 3)}***${key.slice(-3)}`
        : "***MASKED***";
}

function sanitizeHeaders(headers = {}) {
    const sanitizedHeaders = { ...headers };

    if (sanitizedHeaders["Ocp-Apim-Subscription-Key"]) {
        sanitizedHeaders["Ocp-Apim-Subscription-Key"] = maskSubscriptionKey(
            sanitizedHeaders["Ocp-Apim-Subscription-Key"]
        );
    }

    return sanitizedHeaders;
}

function sanitizeSoapBody(body) {
    if (typeof body !== "string" || body.length === 0) {
        return body;
    }

    return body.replace(
        /<password>.*?<\/password>/gis,
        "<password>***MASKED***</password>"
    );
}

function logSoapRequest(operationName, url, body, config) {
    if (!ENABLE_DEBUG) {
        return;
    }

    console.log(`[payments_client] ${operationName} url:`, url);
    console.log(
        `[payments_client] ${operationName} headers:`,
        sanitizeHeaders(config?.headers)
    );
    console.log(`[payments_client] ${operationName} timeout:`, config?.timeout);
    console.log(`[payments_client] ${operationName} body: <omitted for security>`);
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
	const url = nodo_host + "/nodo/node-for-psp/v1";
	
	const config = {
	        timeout: 10000,
	        headers: {
			  'Content-Type': 'text/xml; charset=utf-8',
			  'SOAPAction': '"activatePaymentNoticeV2"',
		      "Ocp-Apim-Subscription-Key": `${process.env.SUBKEY};product=nodo`
	        }
	};
	
	logSoapRequest("activatePaymentNotice", url, body, config);
	
    return post(url, body, config);
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
