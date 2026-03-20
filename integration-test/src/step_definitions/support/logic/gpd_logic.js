const assert = require("assert");
const {
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readCreditorInstitutionIbans,
    readECStationAssociation,
    readStation,
    createCreditorInstitution,
    createCreditorInstitutionIbans,
    createCreditorInstitutionBroker,
    refreshConfig
} = require("../clients/api_config_client");
const { 
    createDebtPosition, 
    publishDebtPosition 
} = require("../clients/gpd_client");
const { 
    activatePaymentNotice,
    sendPaymentOutcome,
    sendRT,
    sendRTV2,
    verifyPaymentNotice,
    getPayment,
    getPaymentV2
} = require("../clients/payments_client");
const { 
    buildDebtPositionDynamicData, 
    buildCreateDebtPositionRequest, 
    buildVerifyPaymentNoticeRequest, 
    buildActivatePaymentNoticeRequest, 
    buildSendPaymentOutcomeRequest, 
    buildSendRTRequest,
    buildGetPaymentReq,
    buildGetPaymentV2Req,
    buildApiConfigServiceCreationCIRequest,
    buildApiConfigServiceCreationIbansRequest,
    buildApiConfigServiceCreationBrokerRequest
} = require("../utility/request_builders");


let toRefresh = false;

const ENABLE_DEBUG = process.env.ENABLE_DEBUG === "true";

function sanitizeForLog(payload) {
    if (payload === undefined || payload === null) {
        return payload;
    }

    if (typeof payload !== "string") {
        return payload;
    }

    return payload.replace(
        /<password>.*?<\/password>/gis,
        "<password>***MASKED***</password>"
    );
}

function debugLog(message, payload, options = {}) {
    if (!ENABLE_DEBUG) {
        return;
    }

    if (options.sensitive === true) {
        console.log(message);
        return;
    }

    if (payload === undefined) {
        console.log(message);
        return;
    }

    console.log(message, sanitizeForLog(payload));
}

function assertAcceptedStatuses(response, acceptedStatuses, operationName) {
    assert.ok(
        acceptedStatuses.includes(response?.status),
        `${operationName} returned unexpected status=${response?.status}, data=${JSON.stringify(response?.data)}`
    );
}


async function assertPaymentTokenExistence(bundle) {
    let paymentTokenRegExp = /<paymentToken>([a-z0-9]+)<\/paymentToken>/
    bundle.debtPosition.paymentToken = paymentTokenRegExp.exec(bundle.responseToCheck.data)?.[1];
    assert.ok(bundle.debtPosition.paymentToken !== undefined);
}

async function executeDebtPositionCreationAndPublishing(bundle) {
    bundle.debtPosition = buildDebtPositionDynamicData(bundle);

    let response = await createDebtPosition(
        bundle.organizationCode,
        buildCreateDebtPositionRequest(bundle.debtPosition, bundle.payer)
    );

    assert.ok(
        response,
        `createDebtPosition returned no response for organizationCode=${bundle.organizationCode}`
    );
    assert.strictEqual(response.status, 201);

    response = await publishDebtPosition(bundle.organizationCode, bundle.debtPosition.iupd);

    assert.ok(
        response,
        `publishDebtPosition returned no response for organizationCode=${bundle.organizationCode}, iupd=${bundle.debtPosition.iupd}`
    );
    assert.strictEqual(response.status, 200);
}

async function executeDebtPositionVerification(bundle) {
    bundle.responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(bundle, bundle.organizationCode));
    assert.strictEqual(bundle.responseToCheck.status, 200);
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);
}

async function executeDebtPositionVerificationAndActivation(bundle) {
    const verifyRequest = buildVerifyPaymentNoticeRequest(bundle, bundle.organizationCode);
    debugLog("[GPD] verifyPaymentNotice request", verifyRequest);

    bundle.responseToCheck = await verifyPaymentNotice(verifyRequest);

    debugLog("[GPD] verifyPaymentNotice response", {
        status: bundle.responseToCheck?.status,
        data: bundle.responseToCheck?.data
    });

    assert.strictEqual(
        bundle.responseToCheck.status,
        200,
        `verifyPaymentNotice failed. status=${bundle.responseToCheck?.status}, data=${JSON.stringify(bundle.responseToCheck?.data)}`
    );
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);

    const activateRequest = buildActivatePaymentNoticeRequest(bundle, bundle.organizationCode);
    debugLog("[GPD] activatePaymentNotice request omitted for security");

    bundle.responseToCheck = await activatePaymentNotice(activateRequest);

    debugLog("[GPD] activatePaymentNotice response", {
        status: bundle.responseToCheck?.status,
        data: bundle.responseToCheck?.data,
        headers: bundle.responseToCheck?.headers
    });

    assert.strictEqual(
        bundle.responseToCheck.status,
        200,
        `activatePaymentNotice failed. status=${bundle.responseToCheck?.status}, data=${JSON.stringify(bundle.responseToCheck?.data)}`
    );
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);
}

async function readCreditorInstitutionBrokerInfo(bundle, brokerId) {
    let response = await readCreditorInstitutionBroker(brokerId);

    // if the test broker does not exist, it is created
    if (response.status === 404) {
        toRefresh = true;
        const body = buildApiConfigServiceCreationBrokerRequest(brokerId);
        response = await createCreditorInstitutionBroker(body);

        // 201 = created, 409 = already exists / idempotent bootstrap
        if (response.status === 409) {
            response = await readCreditorInstitutionBroker(brokerId);
        }
    }

    assertAcceptedStatuses(
        response,
        [200, 201],
        "read/create creditor institution broker"
    );

    bundle.brokerCode = brokerId;
}

async function readCreditorInstitutionInfo(bundle, creditorInstitutionId) {
    await readAndCreateCreditorInstitutionInfo(bundle, creditorInstitutionId, 200);
    bundle.organizationCode = creditorInstitutionId;
    await readValidCreditorInstitutionInfo(bundle);

    let response = await readCreditorInstitutionIbans(bundle.organizationCode);

	debugLog("readCreditorInstitutionIbans status", response?.status);
	debugLog("readCreditorInstitutionIbans data", JSON.stringify(response?.data));

	if (response.status === 404) {
	    bundle.debtPosition.iban = process.env.test_iban || "IT30N0103076271000001823603";
	    console.log("readCreditorInstitutionIbans returned 404, using fallback IBAN configuration");
	    return;
	}

    assert.strictEqual(response.status, 200);

    const data = response?.data;
    const ibansEnhanced =
        data?.ibans_enhanced ??
        data?.ibansEnhanced ??
        (Array.isArray(data) ? data : []);

    assert.ok(
        Array.isArray(ibansEnhanced),
        `Unexpected response from readCreditorInstitutionIbans: ${JSON.stringify(data)}`
    );

	if (ibansEnhanced.length === 0) {
	    bundle.debtPosition.iban = process.env.test_iban || "IT30N0103076271000001823603";
	    console.log("readCreditorInstitutionIbans returned empty list, using fallback IBAN configuration");
	} else {
	    assert.ok(ibansEnhanced[0] !== undefined);
	    bundle.debtPosition.iban = process.env.test_iban || ibansEnhanced[0].iban;
	    console.log("using configured IBAN for debt position");
	}
}

async function readStationInfo(bundle, stationId, brokerId) {
    let response = await readStation(stationId);

    if (response.status === 404) {
        throw new Error(
            `Test misconfigured: station ${stationId} does not exist in ApiConfig`
        );
    }

    assertAcceptedStatuses(
        response,
        [200],
        "read station"
    );

    bundle.stationCode = stationId;

    response = await readECStationAssociation(stationId, bundle.organizationCode);

    if (response.status !== 200) {
        throw new Error(
            `Test misconfigured: no EC-station association found for creditorInstitution=${bundle.organizationCode} and station=${stationId}. ` +
            `Use a preconfigured CI/station pair. ` +
            `readECStationAssociation status=${response?.status}, data=${JSON.stringify(response?.data)}`
        );
    }

    const segregationCode = response.data.segregation_code;

    bundle.debtPosition.iuvPrefix =
        segregationCode < 10
            ? `0${segregationCode}`
            : `${segregationCode}`;
}

async function readInvalidCreditorInstitutionInfo(bundle) {
    await readAndValidateCreditorInstitutionInfo(bundle, process.env.invalid_creditor_institution, 404);
}

async function readValidCreditorInstitutionInfo(bundle) {
    await readAndCreateCreditorInstitutionInfo(bundle, bundle.organizationCode, 200);
}

async function readAndValidateCreditorInstitutionInfo(bundle, organizationCode, status) {
    bundle.debtPosition.fiscalCode = organizationCode;
    let response = await readCreditorInstitution(bundle.debtPosition.fiscalCode);
    assert.strictEqual(response.status, status);
}

async function readAndCreateCreditorInstitutionInfo(bundle, organizationCode, expectedReadStatus) {
    bundle.debtPosition.fiscalCode = organizationCode;
    let response = await readCreditorInstitution(bundle.debtPosition.fiscalCode);

    // if the Creditor Institution does not exist, it is created
    if (response.status === 404) {
        toRefresh = true;
        const body = buildApiConfigServiceCreationCIRequest(organizationCode);
        response = await createCreditorInstitution(body);

        // 409 = already exists / idempotent bootstrap
        if (response.status === 409) {
            response = await readCreditorInstitution(bundle.debtPosition.fiscalCode);
        }
    }

    assertAcceptedStatuses(
        response,
        [expectedReadStatus, 200, 201],
        "read/create creditor institution"
    );
}

async function sendActivatePaymentNoticeRequest(bundle) {
    bundle.responseToCheck = await activatePaymentNotice(
        buildActivatePaymentNoticeRequest(bundle, bundle.debtPosition.fiscalCode)
    );
}

async function sendSendPaymentOutcomeRequest(bundle) {
    bundle.responseToCheck = await sendPaymentOutcome(buildSendPaymentOutcomeRequest(bundle));
}

async function sendSendRTRequest(bundle) {
    bundle.responseToCheck = await sendRT(
        buildSendRTRequest(bundle, bundle.debtPosition.fiscalCode)
    );
}
async function sendSendRTV2Request(bundle) {
    bundle.responseToCheck = await sendRTV2(
        buildSendRTRequest(bundle, bundle.debtPosition.fiscalCode)
    );
}

async function sendVerifyPaymentNoticeRequest(bundle) {
    bundle.responseToCheck = await verifyPaymentNotice(
        buildVerifyPaymentNoticeRequest(bundle, bundle.debtPosition.fiscalCode)
    );
}

async function sendGetPaymentRequest(bundle) {
    bundle.responseToCheck = await getPayment(buildGetPaymentReq(bundle, bundle.debtPosition.fiscalCode));
}

async function sendGetPaymentV2Request(bundle) {
    bundle.responseToCheck = await getPaymentV2(buildGetPaymentV2Req(bundle, bundle.debtPosition.fiscalCode));
}

async function refreshNodeConfig(timeout) {
    if (toRefresh) {
        const response = await refreshConfig();
        assert.strictEqual(response.status, 200);
        toRefresh = false;

        console.log("--> execution test is stopped for [" + timeout + "] seconds to update the cache. Please wait..");
        await new Promise(resolve => setTimeout(resolve, timeout * 1000));
        console.log("--> end of the wait, test execution resumes.");
    }
}


module.exports = {
    assertPaymentTokenExistence,
    executeDebtPositionCreationAndPublishing,
    executeDebtPositionVerification,
    executeDebtPositionVerificationAndActivation,
    readCreditorInstitutionBrokerInfo,
    readCreditorInstitutionInfo,
    readInvalidCreditorInstitutionInfo,
    readStationInfo,
    readValidCreditorInstitutionInfo,
    sendActivatePaymentNoticeRequest,
    sendSendRTRequest,
    sendSendRTV2Request,
    sendSendPaymentOutcomeRequest,
    sendVerifyPaymentNoticeRequest,
    sendGetPaymentRequest,
    sendGetPaymentV2Request,
    refreshNodeConfig
}
