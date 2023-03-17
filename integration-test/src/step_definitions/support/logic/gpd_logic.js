const assert = require("assert");
const {
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readCreditorInstitutionIbans,
    readECStationAssociation,
    readStation,
} = require("../clients/api_config_client");
const { 
    createDebtPosition, 
    publishDebtPosition 
} = require("../clients/gpd_client");
const { 
    activatePaymentNotice,
    sendPaymentOutcome,
    sendRT,
    verifyPaymentNotice
} = require("../clients/payments_client");
const { 
    buildDebtPositionDynamicData, 
    buildCreateDebtPositionRequest, 
    buildVerifyPaymentNoticeRequest, 
    buildActivatePaymentNoticeRequest, 
    buildSendPaymentOutcomeRequest, 
    buildSendRTRequest 
} = require("../utility/request_builders");



async function assertPaymentTokenExistence(bundle) {
    let paymentTokenRegExp = /<paymentToken>([a-z0-9]+)<\/paymentToken>/
    bundle.debtPosition.paymentToken = paymentTokenRegExp.exec(bundle.responseToCheck.data)?.[1];
    assert.ok(bundle.debtPosition.paymentToken !== undefined);
}

async function executeDebtPositionCreationAndPublishing(bundle) {
    bundle.debtPosition = buildDebtPositionDynamicData(bundle);
    let response = await createDebtPosition(bundle.organizationCode, buildCreateDebtPositionRequest(bundle.debtPosition, bundle.payer));
    assert.strictEqual(response.status, 201);
    response = await publishDebtPosition(bundle.organizationCode, bundle.debtPosition.iupd);
    assert.strictEqual(response.status, 200);
}

async function executeDebtPositionVerification(bundle) {
    bundle.responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(bundle, bundle.organizationCode));
    assert.strictEqual(bundle.responseToCheck.status, 200);
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);
}

async function executeDebtPositionVerificationAndActivation(bundle) {
    sendVerifyPaymentNoticeRequest(bundle);
    assert.strictEqual(bundle.responseToCheck.status, 200);
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);
    bundle.responseToCheck = await activatePaymentNotice(buildActivatePaymentNoticeRequest(bundle, bundle.organizationCode));
    assert.strictEqual(bundle.responseToCheck.status, 200);
    assert.match(bundle.responseToCheck.data, /<outcome>OK<\/outcome>/);
}

async function readCreditorInstitutionBrokerInfo(bundle, brokerId) {
    let response = await readCreditorInstitutionBroker(brokerId);
    assert.strictEqual(response.status, 200);
    bundle.brokerCode = brokerId;
}

async function readCreditorInstitutionInfo(bundle, creditorInstitutionId) {
    readAndValidateCreditorInstitutionInfo(bundle, creditorInstitutionId, 200);
    bundle.organizationCode = creditorInstitutionId;
    readValidCreditorInstitutionInfo(bundle, creditorInstitutionId, 200);
    response = await readCreditorInstitutionIbans(bundle.organizationCode);
    assert.ok(response.data.ibans[0] !== undefined);
    bundle.debtPosition.iban = response.data.ibans[0].iban;
}

async function readStationInfo(bundle, stationId) {
    let response = await readStation(stationId);
    assert.strictEqual(response.status, 200);
    bundle.stationCode = stationId;
    response = await readECStationAssociation(stationId, bundle.organizationCode);
    assert.strictEqual(response.status, 200);
    bundle.debtPosition.iuvPrefix = response.data.segregation_code < 10 ? `0${response.data.segregation_code}` : `${response.data.segregation_code}`;
}

async function readInvalidCreditorInstitutionInfo(bundle) {
    readAndValidateCreditorInstitutionInfo(bundle, process.env.invalid_creditor_institution, 404);
}

async function readValidCreditorInstitutionInfo(bundle) {
    readAndValidateCreditorInstitutionInfo(bundle, bundle.organizationCode, 200);
}

async function readAndValidateCreditorInstitutionInfo(bundle, organizationCode, status) {
    bundle.debtPosition.fiscalCode = organizationCode;
    let response = await readCreditorInstitution(bundle.debtPosition.fiscalCode);
    assert.strictEqual(response.status, status);
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

async function sendVerifyPaymentNoticeRequest(bundle) {
    bundle.responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(bundle, bundle.debtPosition.fiscalCode));
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
    sendSendPaymentOutcomeRequest,
    sendVerifyPaymentNoticeRequest,
}