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
	let status = 200;
    let response = await readCreditorInstitutionBroker(brokerId);
    // if the test broker does not exist, it is created
    if (response.status == 404){
	   toRefresh = true;
	   // new expected state check
	   status = 201;
	   let body = buildApiConfigServiceCreationBrokerRequest(brokerId);
	   response = await createCreditorInstitutionBroker(body);
	}
    assert.strictEqual(response.status, status);
    bundle.brokerCode = brokerId;
}

async function readCreditorInstitutionInfo(bundle, creditorInstitutionId) {
    await readAndCreateCreditorInstitutionInfo(bundle, creditorInstitutionId, 200);
    bundle.organizationCode = creditorInstitutionId;
    await readValidCreditorInstitutionInfo(bundle, creditorInstitutionId, 200);
    response = await readCreditorInstitutionIbans(bundle.organizationCode);
    // if there is no iban connected to the CI, it is created
    if (response.data.ibans_enhanced.length == 0) {
	   toRefresh = true;
	   let body = buildApiConfigServiceCreationIbansRequest(new Date(new Date().setDate(new Date().getDate() + 7)).toISOString(), 
	   new Date(new Date().setDate(new Date().getDate() + 1)).toISOString());
	   response = await createCreditorInstitutionIbans(bundle.organizationCode, body);
	   assert.strictEqual(response.status, 201);
	   bundle.debtPosition.iban = response.data.iban;
    } else {
	   assert.ok(response.data.ibans_enhanced[0] !== undefined);
       bundle.debtPosition.iban = response.data.ibans_enhanced[0].iban;
    }
}

async function readStationInfo(bundle, stationId, brokerId) {
	let status = 200;
    let response = await readStation(stationId);
    // if the station does not exist, it is created
    if (response.status == 404){
	   toRefresh = true;
	   status = 201;
	   const ip   = process.env.ip;
	   let body = buildApiConfigServiceCreationStationRequest(stationId, brokerId, ip);
	   response = await createStation(body);
    }
    assert.strictEqual(response.status, status);
    bundle.stationCode = stationId;
    response = await readECStationAssociation(stationId, bundle.organizationCode);
    if (response.status == 404){
	   toRefresh = true;
	   body = buildApiConfigServiceCreationECStationAssociation(stationId);
	   response = await createECStationAssociation(bundle.organizationCode, body);
	}
    assert.ok(response.status == 201 || response.status == 200);
    bundle.debtPosition.iuvPrefix = response.data.segregation_code < 10 ? `0${response.data.segregation_code}` : `${response.data.segregation_code}`;
}

async function readInvalidCreditorInstitutionInfo(bundle) {
    readAndValidateCreditorInstitutionInfo(bundle, process.env.invalid_creditor_institution, 404);
}

async function readValidCreditorInstitutionInfo(bundle) {
    readAndCreateCreditorInstitutionInfo(bundle, bundle.organizationCode, 200);
}

async function readAndValidateCreditorInstitutionInfo(bundle, organizationCode, status) {
    bundle.debtPosition.fiscalCode = organizationCode;
    let response = await readCreditorInstitution(bundle.debtPosition.fiscalCode);
    assert.strictEqual(response.status, status);
}

async function readAndCreateCreditorInstitutionInfo(bundle, organizationCode, status) {
    bundle.debtPosition.fiscalCode = organizationCode;
    let response = await readCreditorInstitution(bundle.debtPosition.fiscalCode);
    // if the Creditor Institution does not exist, it is created
    if (response.status == 404){
	   toRefresh = true;
	   // new expected state check
	   status = 201;
	   let body = buildApiConfigServiceCreationCIRequest(organizationCode);
	   response = await createCreditorInstitution(body);
	}
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
