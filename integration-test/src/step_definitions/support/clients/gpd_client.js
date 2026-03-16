const { get, post } = require("../utility/axios_common");

const gpd_host = process.env.gpd_host;
const GPD_TIMEOUT_MS = 60000;

function gpdHealthCheck() {
    return get(gpd_host + `/info`, {
        headers: {
            "Ocp-Apim-Subscription-Key": process.env.SUBKEY
        }
    });
}

async function createDebtPosition(orgId, body) {
    const response = await post(
        gpd_host + `/organizations/${orgId}/debtpositions`,
        body,
        {
            timeout: GPD_TIMEOUT_MS,
            headers: {
                "Ocp-Apim-Subscription-Key": process.env.SUBKEY,
                "Content-Type": "application/json"
            }
        }
    );

    if (!response) {
        throw new Error(
            `createDebtPosition returned no response for orgId=${orgId}`
        );
    }

    return response;
}

async function publishDebtPosition(orgId, iupd) {
    const response = await post(
        gpd_host + `/organizations/${orgId}/debtpositions/${iupd}/publish`,
        "",
        {
            timeout: GPD_TIMEOUT_MS,
            headers: {
                "Ocp-Apim-Subscription-Key": process.env.SUBKEY,
                "Content-Type": "application/json"
            }
        }
    );

    if (!response) {
        throw new Error(
            `publishDebtPosition returned no response for orgId=${orgId}, iupd=${iupd}`
        );
    }

    return response;
}

module.exports = {
    createDebtPosition,
    gpdHealthCheck,
    publishDebtPosition
};