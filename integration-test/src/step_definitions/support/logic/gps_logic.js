const assert = require("assert");
const { createOrganization, createService, deleteOrganization, deleteService } = require("../clients/gps_client");
const { demandPaymentNotice } = require("../clients/payments_client");
const { buildDemandPaymentNoticeRequest, buildGPSOrganizationCreationRequest, buildGPSServiceCreationRequest } = require("../utility/request_builders");


async function createOrganizationInfo(bundle, organizationCode, serviceId) {
    bundle.organizationCode = organizationCode;
    let response = await createOrganization(organizationCode, buildGPSOrganizationCreationRequest(serviceId));    
    assert.ok(response.status === 201 || response.status === 409);
}

async function createServiceInfo(bundle, serviceId) {
    bundle.isExecuting = true;
    bundle.serviceCode = serviceId;
    let response = await createService(buildGPSServiceCreationRequest(serviceId, process.env.donation_host))
    assert.ok(response.status === 201 || response.status === 409);
}

async function executeGPSDataDeletion(bundle) {
    await deleteService(bundle.serviceCode);
    await deleteOrganization(bundle.organizationCode);
}

async function sendInvalidDemandPaymentNoticeRequest(bundle) {
    bundle.serviceData = "PHNlcnZpY2UgeG1sbnM9Imh0dHA6Ly9QdW50b0FjY2Vzc29QU1Auc3Bjb29wLmdvdi5pdC9HZW5lcmFsU2VydmljZSIgeHNpOnNjaGVtYUxvY2F0aW9uPSJodHRwOi8vUHVudG9BY2Nlc3NvUFNQLnNwY29vcC5nb3YuaXQvR2VuZXJhbFNlcnZpY2Ugc2NoZW1hLnhzZCIgeG1sbnM6eHNpPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxL1hNTFNjaGVtYS1pbnN0YW5jZSI+CiAgPGRlc2NyaXB0aW9uPmRvbmF0aW9uPC9kZXNjcmlwdGlvbj4KPC9zZXJ2aWNlPg==";
    bundle.responseToCheck = await demandPaymentNotice(buildDemandPaymentNoticeRequest(bundle));
}

async function sendValidDemandPaymentNoticeRequest(bundle) {
    bundle.serviceData = "PHNlcnZpY2UgeG1sbnM9Imh0dHA6Ly9QdW50b0FjY2Vzc29QU1Auc3Bjb29wLmdvdi5pdC9HZW5lcmFsU2VydmljZSIgeHNpOnNjaGVtYUxvY2F0aW9uPSJodHRwOi8vUHVudG9BY2Nlc3NvUFNQLnNwY29vcC5nb3YuaXQvR2VuZXJhbFNlcnZpY2Ugc2NoZW1hLnhzZCIgeG1sbnM6eHNpPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxL1hNTFNjaGVtYS1pbnN0YW5jZSI+CiAgPGFtb3VudD4xMDAuMDA8L2Ftb3VudD4KICA8ZGVzY3JpcHRpb24+ZG9uYXRpb248L2Rlc2NyaXB0aW9uPgo8L3NlcnZpY2U+";
    bundle.responseToCheck = await demandPaymentNotice(buildDemandPaymentNoticeRequest(bundle));
}


module.exports = {
    createOrganizationInfo,
    createServiceInfo,
    executeGPSDataDeletion,
    sendInvalidDemandPaymentNoticeRequest,
    sendValidDemandPaymentNoticeRequest,
}