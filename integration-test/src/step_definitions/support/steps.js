const {Given, When, Then, After, AfterAll, Before, AfterStep} = require('@cucumber/cucumber')
const {
    demandPaymentNotice,
    getPaymentRequest,
    healthCheck,
    sendRTRequest,
    verifyPaymentNotice
} = require("./payments_client");
const {gpsHealthCheck, createOrganization, deleteOrganization, createService, deleteService} = require("./gps_client");
const {iuvGenHealthCheck} = require("./iuv_generator_client");
const {donationHealthCheck} = require("./donation_service_client");
const {
    apiConfigHealthCheck,
    createECStationAssociation,
    createStation,
    deleteECStationAssociation,
    deleteStation,
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readECStationAssociation,
    readStation
} = require("./api_config_client");
const {
    createDebtPosition,
    gpdHealthCheck,
    publishDebtPosition
} = require("./gpd_client");
const assert = require("assert");
const fs = require("fs");
const { 
    buildCreateDebtPositionRequest,
    buildCreateECStationRelationRequest,
    buildDebtPositionDynamicData,
    buildGPSOrganizationCreationRequest,
    buildGPSServiceCreationRequest,
    buildGetPaymentRequest,
    buildInvalidDemandPaymentNoticeRequest,
    buildSendRTRequest,
    buildValidDemandPaymentNoticeRequest,
    buildVerifyPaymentNoticeRequest
} = require('./request_builders');

let gpsSessionBundle = {
    isExecuting: false,
    responseToCheck: undefined,
    serviceCode: undefined,
    organizationCode: undefined
}

let responseToCheck = undefined;

let gpdSessionBundle = {
    isExecuting: false,
    debtPosition: {
        iupd: undefined,
        iuv1: undefined,
        iuv2: undefined,
        iuv3: undefined,
        dueDate: undefined,
        retentionDate: undefined,
        transferId1: undefined,
        transferId2: undefined,
        receiptId: undefined,
    },
    organizationCode: undefined,
    brokerCode: undefined,
    stationCode: undefined,
}

/* 'Given' precondition for health checks */
Given('Payments running', async function () {
    const response = await healthCheck();
    assert.strictEqual(response.status, 200);
});
Given('GPS running', async function () {
    const response = await gpsHealthCheck();
    assert.strictEqual(response.status, 200);
});
Given('GPD running', async function () {
    const response = await gpdHealthCheck();
    assert.strictEqual(response.status, 200);
});
Given('IUV Generator running', async function () {
	const response = await iuvGenHealthCheck();
    assert.strictEqual(response.status, 200);
});
Given('DonationService running', async function () {
	const response = await donationHealthCheck();
    assert.strictEqual(response.status, 200);
});
Given('ApiConfig running', async function () {
    const response = await apiConfigHealthCheck();
    assert.strictEqual(response.status, 200);
});


/* Given precondition for validating the flow - GPD section */
Given('the creditor institution {string}', async function (orgId) {
    gpdSessionBundle.organizationCode = orgId;
    let response = await readCreditorInstitution(orgId);
    assert.strictEqual(response.status, 200);
});
Given('the creditor institution broker {string}', async function (brokerId) {
    gpdSessionBundle.brokerCode = brokerId;
    let response = await readCreditorInstitutionBroker(brokerId);
    assert.strictEqual(response.status, 200);
});
Given('a valid debt position', async function () {
    gpdSessionBundle.debtPosition = buildDebtPositionDynamicData();
    let response = await createDebtPosition(gpdSessionBundle.organizationCode, buildCreateDebtPositionRequest(gpdSessionBundle.debtPosition));
    assert.strictEqual(response.status, 201);
    response = await publishDebtPosition(gpdSessionBundle.organizationCode, gpdSessionBundle.debtPosition.iupd);
    assert.strictEqual(response.status, 200);
});


/* 'Given' precondition for retrieving data - GPD section */
Given('the station {string} related to creditor institution', async function (stationId) {
    gpdSessionBundle.stationCode = stationId;
    // let response = await createStation(buildCreateStationRequest(gpdSessionBundle.brokerCode, stationId));
    // assert.ok(response.status === 201 || response.status === 409);
    response = await createECStationAssociation(gpdSessionBundle.organizationCode, buildCreateECStationRelationRequest(stationId));
    assert.ok(response.status === 201 || response.status === 409, response.data);
});
Given('the station {string} not related to creditor institution', async function (stationId) {
    gpdSessionBundle.stationCode = stationId;
    // let response = await readStation(stationId, gpdSessionBundle.organizationCode);
    // assert.strictEqual(response.status, 404);
    await deleteECStationAssociation(stationId, gpdSessionBundle.organizationCode);
    let response = await readECStationAssociation(stationId, gpdSessionBundle.organizationCode);
    assert.strictEqual(response.status, 404);
});


/* 'When' clauses for retrieving data to be analyzed - GPD section */
When('the client sends the VerifyPaymentNoticeRequest', async function () {
    responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(gpdSessionBundle));
});
When('the client sends the GetPaymentRequest', async function () {
    const response = await readECStationAssociation(gpdSessionBundle.stationCode, gpdSessionBundle.organizationCode);
    responseToCheck = await getPaymentRequest(buildGetPaymentRequest(gpdSessionBundle));
});
When('the client sends the SendRTRequest', async function () {
    responseToCheck = await sendRTRequest(buildSendRTRequest(gpdSessionBundle));
});


/* 'Given' precondition for retrieving data - GPS section */
Given('the service {string} for donations', async function (serviceId) {
    gpsSessionBundle.isExecuting = true;
    let response = await createService(buildGPSServiceCreationRequest(serviceId, process.env.donation_host))
    assert.strictEqual(response.status, 201);
});
Given('the creditor institution {string} enrolled to donation service {string}', async function (orgId, serviceId) {
    gpsSessionBundle.organizationCode = orgId;
    await deleteOrganization(organizationCode);
    let response = await createOrganization(orgId, buildGPSOrganizationCreationRequest(serviceId));    
    assert.strictEqual(response.status, 201);
});


/* 'When' clauses for retrieving data to be analyzed - GPS section */
When('the client sends the DemandPaymentNoticeRequest', async function () {
    responseToCheck = await demandPaymentNotice(buildValidDemandPaymentNoticeRequest(organizationCode));
});
When('the client sends a wrong DemandPaymentNoticeRequest', async function () {
    responseToCheck = await demandPaymentNotice(buildInvalidDemandPaymentNoticeRequest(organizationCode));
});


/* 'Then' clauses for assering retrieved data */
Then('the client receives status code {int}', async function (statusCode) {
    assert.strictEqual(responseToCheck.status, statusCode);
});
Then('the client retrieves the amount {string} in the response', async function (amount) {
    const data = responseToCheck.data;
    assert.match(data, /<outcome>OK<\/outcome>/);
    assert.match(data, new RegExp(`<amount>${amount}</amount>`, "g"));
});
Then('the client receives an OK in the response', async function () {
    const data = responseToCheck.data;
    assert.match(data, /<outcome>OK<\/outcome>/);
});
Then('the client receives a KO in the response', async function () {
    assert.match(responseToCheck.data, /<outcome>KO<\/outcome>/);
});
Then('the client receives a KO with the {string} fault code error', async function (fault) {
    assert.match(responseToCheck.data, /<outcome>KO<\/outcome>/);
    assert.match(responseToCheck.data, new RegExp(`<faultCode>${fault}</faultCode>`, "g"));
});


Before({tags: '@GPDScenario'}, async function () {
    console.log("\nGPD - Starting new scenario");
    responseToCheck = undefined;
    gpdSessionBundle.isExecuting = true;
    await deleteECStationAssociation(gpdSessionBundle.organizationCode, gpdSessionBundle.stationCode);
    //await deleteStation(gpdSessionBundle.stationCode);
});

AfterAll(async function () {
    if (gpsSessionBundle.isExecuting) {
        await deleteService(gpsSessionBundle.serviceCode);
        await deleteOrganization(gpsSessionBundle.organizationCode);        
    } else if (gpdSessionBundle.isExecuting) {
        console.log("\nGPD - Final delete station and EC-Station relation");
        await deleteECStationAssociation(gpdSessionBundle.organizationCode, gpdSessionBundle.stationCode);
        //await deleteStation(gpdSessionBundle.stationCode);
    }
});
