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
    createStation,
    createECStationAssociation,
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
    buildApiConfigServiceCreationBrokerRequest,
    buildApiConfigServiceCreationStationRequest,
    buildApiConfigServiceCreationECStationAssociation 
} = require("../utility/request_builders");


let toRefresh = false;

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
    await sendVerifyPaymentNoticeRequest(bundle);
    assert.strictEqual(bundle.responseToCheck.status, 200);
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);
    bundle.responseToCheck = await activatePaymentNotice(buildActivatePaymentNoticeRequest(bundle, bundle.organizationCode));
    assert.strictEqual(bundle.responseToCheck.status, 200);
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

    console.log("readCreditorInstitutionIbans status:", response?.status);
    console.log("readCreditorInstitutionIbans data:", JSON.stringify(response?.data));

    if (response.status === 404) {
        response = await createMissingIban(bundle);
        bundle.debtPosition.iban = response.data.iban;
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
        response = await createMissingIban(bundle);
        bundle.debtPosition.iban = response.data.iban;
    } else {
        assert.ok(ibansEnhanced[0] !== undefined);
        bundle.debtPosition.iban = ibansEnhanced[0].iban;
    }
}

async function createMissingIban(bundle) {
    toRefresh = true;

	const now = new Date();
	const validityDate = new Date(now.getTime() + 15 * 1000).toISOString(); // +15 seconds
	const dueDate = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000).toISOString(); // +7 days

	const body = buildApiConfigServiceCreationIbansRequest(dueDate, validityDate);

    let response = await createCreditorInstitutionIbans(bundle.organizationCode, body);

    console.log("createCreditorInstitutionIbans status:", response?.status);
    console.log("createCreditorInstitutionIbans data:", JSON.stringify(response?.data));

    // 201 = created
    // 409 = already exists
    assertAcceptedStatuses(
        response,
        [201, 409],
        "create creditor institution iban"
    );

    if (response.status === 201 && response?.data?.iban) {
        return response;
    }

    // If 409, try to read back the ibans again
    response = await readCreditorInstitutionIbans(bundle.organizationCode);

    console.log("readCreditorInstitutionIbans after create status:", response?.status);
    console.log("readCreditorInstitutionIbans after create data:", JSON.stringify(response?.data));

    const data = response?.data;
    const ibansEnhanced =
        data?.ibans_enhanced ??
        data?.ibansEnhanced ??
        (Array.isArray(data) ? data : []);

    if (response.status === 200 && Array.isArray(ibansEnhanced) && ibansEnhanced.length > 0) {
        return {
            status: 200,
            data: {
                iban: ibansEnhanced[0].iban
            }
        };
    }

    throw new Error(
        `Unable to resolve iban after create conflict. status=${response?.status}, data=${JSON.stringify(response?.data)}`
    );
}

async function readStationInfo(bundle, stationId, brokerId) {
    let response = await readStation(stationId);

    // if the station does not exist, it is created
    if (response.status === 404) {
        toRefresh = true;
        const ip = process.env.ip;
        const stationBody = buildApiConfigServiceCreationStationRequest(stationId, brokerId, ip);
        response = await createStation(stationBody);

        // 409 = already exists / idempotent bootstrap
        if (response.status === 409) {
            response = await readStation(stationId);
        }
    }

    assertAcceptedStatuses(
        response,
        [200, 201],
        "read/create station"
    );

    bundle.stationCode = stationId;

    response = await readECStationAssociation(stationId, bundle.organizationCode);

    // association already exists and is readable
    if (response.status === 200) {
        bundle.debtPosition.iuvPrefix =
            response.data.segregation_code < 10
                ? `0${response.data.segregation_code}`
                : `${response.data.segregation_code}`;
        return;
    }

    // association missing: create it, but do not require immediate readability
    if (response.status === 404) {
        toRefresh = true;

        const associationBody = buildApiConfigServiceCreationECStationAssociation(stationId);
        const createResponse = await createECStationAssociation(bundle.organizationCode, associationBody);

        console.log("createECStationAssociation status:", createResponse?.status);
        console.log("createECStationAssociation data:", JSON.stringify(createResponse?.data));

        // 201 = created now
        // 409 = already exists
        assertAcceptedStatuses(
            createResponse,
            [201, 409],
            "create EC-station association"
        );

        // Use the segregation code from the request body when the association has just been created
        // or when the backend reports a conflict but the relation is logically already there.
        bundle.debtPosition.iuvPrefix =
            associationBody.segregation_code < 10
                ? `0${associationBody.segregation_code}`
                : `${associationBody.segregation_code}`;

        return;
    }

    // any other status is unexpected
    assertAcceptedStatuses(
        response,
        [200],
        "read EC-station association"
    );
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
    bundle.responseToCheck = await activatePaymentNotice(buildActivatePaymentNoticeRequest(bundle, bundle.debtPosition.fiscalCode));
}

async function sendSendPaymentOutcomeRequest(bundle) {
    bundle.responseToCheck = await sendPaymentOutcome(buildSendPaymentOutcomeRequest(bundle));
}

async function sendSendRTRequest(bundle) {
    bundle.responseToCheck = await sendRT(buildSendRTRequest(bundle, bundle.debtPosition.fiscalCode));
}
async function sendSendRTV2Request(bundle) {
    bundle.responseToCheck = await sendRTV2(buildSendRTRequest(bundle, bundle.debtPosition.fiscalCode));
}

async function sendVerifyPaymentNoticeRequest(bundle) {
    bundle.responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(bundle, bundle.debtPosition.fiscalCode));
}

async function sendGetPaymentRequest(bundle) {
    bundle.responseToCheck = await getPayment(buildGetPaymentReq(bundle, bundle.debtPosition.fiscalCode));
}

async function sendGetPaymentV2Request(bundle) {
    bundle.responseToCheck = await getPaymentV2(buildGetPaymentV2Req(bundle, bundle.debtPosition.fiscalCode));
}

// N.B.: even by launching the configuration refresh, if it was necessary to create a new IBAN, this will not be visible until the validity date is reached
async function refreshNodeConfig(timeout) {
    if (toRefresh){
		let response = await refreshConfig();
		assert.strictEqual(response.status, 200);
		toRefresh = false;
		console.log("--> execution test is stopped for ["+timeout+"] seconds to update the cache. Please wait..");
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
