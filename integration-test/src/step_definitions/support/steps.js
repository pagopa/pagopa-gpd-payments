const {Given, When, Then, After, AfterAll, Before, AfterStep} = require('@cucumber/cucumber')
const {
    activatePaymentNotice,
    demandPaymentNotice,
    healthCheck,
    sendPaymentOutcome,
    sendRT,
    verifyPaymentNotice,
} = require("./payments_client");
const {gpsHealthCheck, createOrganization, deleteOrganization, createService, deleteService} = require("./gps_client");
const {iuvGenHealthCheck} = require("./iuv_generator_client");
const {donationHealthCheck} = require("./donation_service_client");
const {
    apiConfigHealthCheck,
    readCreditorInstitution,
    readCreditorInstitutionBroker,
    readStation,
    readECStationAssociation,
    readCreditorInstitutionIbans
} = require("./api_config_client");
const {
    createDebtPosition,
    gpdHealthCheck,
    publishDebtPosition
} = require("./gpd_client");
const assert = require("assert");
const { 
    buildActivatePaymentNoticeRequest,
    buildCreateDebtPositionRequest,
    buildDebtPositionDynamicData,
    buildGPSOrganizationCreationRequest,
    buildGPSServiceCreationRequest,
    buildInvalidDemandPaymentNoticeRequest,
    buildValidDemandPaymentNoticeRequest,
    buildVerifyPaymentNoticeRequest,
    buildSendPaymentOutcomeRequest,
    buildSendRTRequest,
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
        iuvPrefix: undefined,
        iban: undefined,
        dueDate: undefined,
        retentionDate: undefined,
        transferId1: undefined,
        transferId2: undefined,
        receiptId: undefined,
        amount: undefined,
        pspId: undefined,
        pspBrokerId: undefined,
        pspChannelId: undefined,
        pspName: undefined,
        pspFiscalCode: undefined,
        fiscalCode: undefined,
        paymentToken: undefined,
        applicationDate: undefined,
        transferDate: undefined,
    },
    payer: {
        name: "Michele Ventimiglia",
        fiscalCode: "VNTMHL76M09H501D",
        streetName: "via Washington",
        civicNumber: "11",
        postalCode: "89812",
        city: "Pizzo Calabro",
        province: "VV",
        region: "CA",
        country: "IT",
        email: "micheleventimiglia@skilabmail.com",
        phone: "333-123456789",
        companyName: "SkyLab Inc.",
        officeName: "SkyLab - Sede via Washington"
    },
    organizationCode: undefined,
    brokerCode: undefined,
    stationCode: undefined,
}


/* 
 *  'Given' precondition for health checks on various services. 
 */
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


/* 
 *  GPD section.
 *  'Given' precondition for validating the entities to be used. 
 */
Given('the creditor institution {string}', async function (orgId) {
    let response = await readCreditorInstitution(orgId);
    assert.strictEqual(response.status, 200);
    gpdSessionBundle.organizationCode = orgId;
    response = await readCreditorInstitutionIbans(gpdSessionBundle.organizationCode);
    assert.ok(response.data.ibans[0] !== undefined);
    gpdSessionBundle.debtPosition.iban = response.data.ibans[0].iban;
});
Given('the creditor institution broker {string}', async function (brokerId) {
    let response = await readCreditorInstitutionBroker(brokerId);
    assert.strictEqual(response.status, 200);
    gpdSessionBundle.brokerCode = brokerId;
});
Given('the station {string}', async function (stationId) {
    let response = await readStation(stationId);
    assert.strictEqual(response.status, 200);
    gpdSessionBundle.stationCode = stationId;
    response = await readECStationAssociation(stationId, gpdSessionBundle.organizationCode);
    assert.strictEqual(response.status, 200);
    gpdSessionBundle.debtPosition.iuvPrefix = response.data.segregation_code < 10 ? `0${response.data.segregation_code}` : `${response.data.segregation_code}`;
});


/* 
 *  GPD section.
 *  'Given' precondition for validating, verifying and activate the debt position to be used.
 */
Given('a valid debt position', async function () {
    gpdSessionBundle.debtPosition = buildDebtPositionDynamicData(gpdSessionBundle);
    let response = await createDebtPosition(gpdSessionBundle.organizationCode, buildCreateDebtPositionRequest(gpdSessionBundle.debtPosition, gpdSessionBundle.payer));
    assert.strictEqual(response.status, 201);
    response = await publishDebtPosition(gpdSessionBundle.organizationCode, gpdSessionBundle.debtPosition.iupd);
    assert.strictEqual(response.status, 200);
});
Given('a proper verification of debt position', async function () {
    responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(gpdSessionBundle, gpdSessionBundle.organizationCode));
    assert.strictEqual(responseToCheck.status, 200);
    assert.match(responseToCheck.data, /<outcome>OK<\/outcome>/);
});
Given('a proper activation of debt position', async function () {
    responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(gpdSessionBundle, gpdSessionBundle.organizationCode));
    assert.strictEqual(responseToCheck.status, 200);
    assert.match(responseToCheck.data, /<outcome>OK<\/outcome>/);
    responseToCheck = await activatePaymentNotice(buildActivatePaymentNoticeRequest(gpdSessionBundle, gpdSessionBundle.organizationCode));
    assert.strictEqual(responseToCheck.status, 200);
    assert.match(responseToCheck.data, /<outcome>OK<\/outcome>/);
});


/* 
 *  GPD section.
 *  'Given' precondition for validating the creditor institution's fiscal code to be used.
 */
Given('an invalid fiscal code', async function () {
    gpdSessionBundle.debtPosition.fiscalCode = process.env.invalid_creditor_institution;
    let response = await readCreditorInstitution(gpdSessionBundle.debtPosition.fiscalCode);
    assert.strictEqual(response.status, 404);
});
Given('a valid fiscal code', async function () {
    gpdSessionBundle.debtPosition.fiscalCode = gpdSessionBundle.organizationCode;
    let response = await readCreditorInstitution(gpdSessionBundle.debtPosition.fiscalCode);
    assert.strictEqual(response.status, 200);
});


/* 
 *  GPD section.
 *  'When' clauses for sending SOAP actions for the payments flow.
 */
When('the client sends the VerifyPaymentNoticeRequest', async function () {
    responseToCheck = await verifyPaymentNotice(buildVerifyPaymentNoticeRequest(gpdSessionBundle, gpdSessionBundle.debtPosition.fiscalCode));
});
When('the client sends the ActivatePaymentNoticeRequest to Nodo', async function () {
    responseToCheck = await activatePaymentNotice(buildActivatePaymentNoticeRequest(gpdSessionBundle, gpdSessionBundle.debtPosition.fiscalCode));
});
When('the client sends the SendPaymentOutcomeRequest to Nodo', async function () {
    responseToCheck = await sendPaymentOutcome(buildSendPaymentOutcomeRequest(gpdSessionBundle));
});
When('the client sends the SendRTRequest', async function () {
    responseToCheck = await sendRT(buildSendRTRequest(gpdSessionBundle, gpdSessionBundle.debtPosition.fiscalCode));
});


/* 
 *  GPS section.
 *  'Given' precondition for retrieving data
 */
Given('the service {string} for donations', async function (serviceId) {
    gpsSessionBundle.isExecuting = true;
    gpsSessionBundle.serviceCode = serviceId;
    let response = await createService(buildGPSServiceCreationRequest(serviceId, process.env.donation_host))
    assert.ok(response.status === 201 || response.status === 409);
});
Given('the creditor institution {string} enrolled to donation service {string}', async function (orgId, serviceId) {
    gpsSessionBundle.organizationCode = orgId;
    let response = await createOrganization(orgId, buildGPSOrganizationCreationRequest(serviceId));    
    assert.ok(response.status === 201 || response.status === 409);
});


/* 
 *  GPS section.
 *  'When' clauses for retrieving data to be analyzed.
 */
When('the client sends the DemandPaymentNoticeRequest', async function () {
    responseToCheck = await demandPaymentNotice(buildValidDemandPaymentNoticeRequest(gpsSessionBundle.organizationCode));
});
When('the client sends a wrong DemandPaymentNoticeRequest', async function () {
    responseToCheck = await demandPaymentNotice(buildInvalidDemandPaymentNoticeRequest(gpsSessionBundle.organizationCode));
});


/* 
 *  'Then' clauses for assering retrieved data 
 */
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
Then('the payment token is extracted', async function () {
    let paymentTokenRegExp = /<paymentToken>([a-z0-9]+)<\/paymentToken>/
    gpdSessionBundle.debtPosition.paymentToken = paymentTokenRegExp.exec(responseToCheck.data)?.[1];
    assert.ok(gpdSessionBundle.debtPosition.paymentToken !== undefined);
});



Before({tags: '@GPDScenario'}, async function () {
    console.log("\nGPD - Starting new scenario");
});

Before({tags: '@GPSScenario'}, async function () {
    console.log("\nGPS - Starting new scenario");
});

AfterAll(async function () {
    if (gpsSessionBundle.isExecuting) {
        await deleteService(gpsSessionBundle.serviceCode);
        await deleteOrganization(gpsSessionBundle.organizationCode);
    }
});
