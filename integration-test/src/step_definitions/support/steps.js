const {Given, When, Then, AfterAll, Before} = require('@cucumber/cucumber')
const { 
    executeHealthCheckForAPIConfig,
    executeHealthCheckForDonations,
    executeHealthCheckForGPD,
    executeHealthCheckForGPDPayments,
    executeHealthCheckForGPS,
    executeHealthCheckForIUVGenerator
} = require('./logic/health_checks_logic');
const { 
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
    sendSendPaymentOutcomeRequest,
    sendSendRTRequest,
    sendSendRTV2Request,
    sendVerifyPaymentNoticeRequest,
    sendGetPaymentRequest,
    sendGetPaymentV2Request
} = require('./logic/gpd_logic');
const { assertAmount, assertFaultCode, assertOutcome, assertStatusCode, executeAfterAllStep, assertPaymentAmount, assertIbanInTransferList } = require('./logic/common_logic');
const { createOrganizationInfo, createServiceInfo, sendInvalidDemandPaymentNoticeRequest, sendValidDemandPaymentNoticeRequest } = require('./logic/gps_logic');
const { gpdSessionBundle, gpsSessionBundle } = require('./utility/data');
const { getValidBundle } = require('./utility/helpers');



/* 
 *  'Given' precondition for health checks on various services. 
 */
Given('Payments running', () => executeHealthCheckForGPDPayments());
Given('GPS running', () => executeHealthCheckForGPS());
Given('GPD running', () => executeHealthCheckForGPD());
Given('IUV Generator running', () => executeHealthCheckForIUVGenerator());
Given('DonationService running', () => executeHealthCheckForDonations());
Given('ApiConfig running', () => executeHealthCheckForAPIConfig());


/* 
 *  GPD section.
 *  'Given' precondition for validating the entities to be used. 
 */
Given('the creditor institution {string}', (orgId) => readCreditorInstitutionInfo(gpdSessionBundle, orgId));
Given('the creditor institution broker {string}', (brokerId) => readCreditorInstitutionBrokerInfo(gpdSessionBundle, brokerId));
Given('the station {string}', (stationId) => readStationInfo(gpdSessionBundle, stationId));


/* 
 *  GPD section.
 *  'Given' precondition for validating, verifying and activate the debt position to be used.
 */
Given('a valid debt position', () => executeDebtPositionCreationAndPublishing(gpdSessionBundle));
Given('a proper verification of debt position', () => executeDebtPositionVerification(gpdSessionBundle));
Given('a proper activation of debt position', () => executeDebtPositionVerificationAndActivation(gpdSessionBundle));


/* 
 *  GPD section.
 *  'Given' precondition for validating the creditor institution's fiscal code to be used.
 */
Given('an invalid fiscal code', () => readInvalidCreditorInstitutionInfo(gpdSessionBundle));
Given('a valid fiscal code', () => readValidCreditorInstitutionInfo(gpdSessionBundle));


/* 
 *  GPD section.
 *  'When' clauses for sending SOAP actions for the payments flow.
 */
When('the client sends the VerifyPaymentNoticeRequest', () => sendVerifyPaymentNoticeRequest(gpdSessionBundle));
When('the client sends the ActivatePaymentNoticeRequest to Nodo', () => sendActivatePaymentNoticeRequest(gpdSessionBundle));
When('the client sends the SendPaymentOutcomeRequest to Nodo', () => sendSendPaymentOutcomeRequest(gpdSessionBundle));
When('the client sends the SendRTRequest', () => sendSendRTRequest(gpdSessionBundle));
When('the client sends the SendRTV2Request', () => sendSendRTV2Request(gpdSessionBundle));
When('the client sends the GetPaymentRequest', () => sendGetPaymentRequest(gpdSessionBundle))
When('the client sends the GetPaymentV2Request', () => sendGetPaymentV2Request(gpdSessionBundle))


/* 
 *  GPS section.
 *  'Given' precondition for retrieving data
 */
Given('the service {string} for donations', (serviceId) => createServiceInfo(gpsSessionBundle, serviceId));
Given('the creditor institution {string} enrolled to donation service {string}', (orgId, serviceId) => createOrganizationInfo(gpsSessionBundle, orgId, serviceId));


/* 
 *  GPS section.
 *  'When' clauses for retrieving data to be analyzed.
 */
When('the client sends the DemandPaymentNoticeRequest', () => sendValidDemandPaymentNoticeRequest(gpsSessionBundle));
When('the client sends a wrong DemandPaymentNoticeRequest', () => sendInvalidDemandPaymentNoticeRequest(gpsSessionBundle));


/* 
 *  'Then' clauses for assering retrieved data 
 */
Then('the client receives status code {int}', (statusCode) => assertStatusCode(getValidBundle(gpdSessionBundle, gpsSessionBundle), statusCode));
Then('the client retrieves the amount {string} in the response', (amount) => assertAmount(getValidBundle(gpdSessionBundle, gpsSessionBundle), amount));
Then('the client receives an OK in the response', () => assertOutcome(getValidBundle(gpdSessionBundle, gpsSessionBundle), "OK"));
Then('the client receives a KO in the response', () => assertOutcome(getValidBundle(gpdSessionBundle, gpsSessionBundle), "KO"));
Then('the client receives a KO with the {string} fault code error', (fault) => assertFaultCode(getValidBundle(gpdSessionBundle, gpsSessionBundle), fault));
Then('the payment token is extracted', () => assertPaymentTokenExistence(getValidBundle(gpdSessionBundle, gpsSessionBundle)));
Then('the client retrieves the payment amount {string} in the response', (amount) => assertPaymentAmount(getValidBundle(gpdSessionBundle, gpsSessionBundle), amount));
Then('the client retrieves the IBANs in the response', () => assertIbanInTransferList(getValidBundle(gpdSessionBundle, gpsSessionBundle)));



Before({tags: '@GPDScenario'}, async function () {
    console.log("\nGPD - Starting new scenario");
    gpdSessionBundle.isExecuting = true;
    gpsSessionBundle.isExecuting = false;
});

Before({tags: '@GPSScenario'}, async function () {
    console.log("\nGPS - Starting new scenario");
    gpdSessionBundle.isExecuting = false;
    gpsSessionBundle.isExecuting = true;
});

AfterAll(() => executeAfterAllStep(gpsSessionBundle));
