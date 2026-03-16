const {get, post} = require("../utility/axios_common");

const gpd_host = process.env.gpd_host;

function gpdHealthCheck() {
    return get(gpd_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    });
}

async function postWithRetry(url, body, config, operationName, maxAttempts = 3) {
    let lastResponse;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        lastResponse = await post(url, body, config);

        if (lastResponse && lastResponse.status < 500) {
            return lastResponse;
        }

        console.log(`[GPD] ${operationName} attempt ${attempt}/${maxAttempts} failed`, {
            status: lastResponse?.status,
            data: lastResponse?.data
        });

        if (attempt < maxAttempts) {
            await new Promise(resolve => setTimeout(resolve, 1500));
        }
    }

    return lastResponse;
}

function createDebtPosition(orgId, body) {
    console.log("[GPD] createDebtPosition request", {
        orgId,
        iupd: body?.iupd,
        iuv: body?.paymentOption?.[0]?.iuv,
        iban: body?.paymentOption?.[0]?.transfer?.[0]?.iban
    });

    return postWithRetry(
        gpd_host + `/organizations/${orgId}/debtpositions`,
        body,
        {
            timeout: 15000,
            headers: {
                "Ocp-Apim-Subscription-Key": process.env.SUBKEY,
                "Content-Type": "application/json"
            }
        },
        "createDebtPosition"
    );
}

function publishDebtPosition(orgId, iupd) {
    console.log("[GPD] publishDebtPosition request", { orgId, iupd });

    return postWithRetry(
        gpd_host + `/organizations/${orgId}/debtpositions/${iupd}/publish`,
        "",
        {
            timeout: 15000,
            headers: {
                "Ocp-Apim-Subscription-Key": process.env.SUBKEY,
                "Content-Type": "application/json"
            }
        },
        "publishDebtPosition"
    );
}

module.exports = {
    createDebtPosition,
    gpdHealthCheck,
    publishDebtPosition
};