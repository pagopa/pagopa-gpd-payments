const { paDemandPaymentNotice } = require("../clients/payments_client");
const { buildPaDemandPaymentNoticeRequest } = require("../utility/request_builders");

// The vertical service is now statically mapped by "idServizio" in the GPD
// Payments configuration (service.gps.vertical.service-map.<idServizio>), so
// there is no organization/service to create at runtime anymore: the test
// only needs to point the request to a pre-configured serviceId.
function setVerticalServiceInfo(bundle, organizationCode, serviceId) {
    bundle.isExecuting = true;
    bundle.organizationCode = organizationCode;
    bundle.serviceCode = serviceId;
}

async function sendInvalidDemandPaymentNoticeRequest(bundle) {
    bundle.serviceData = "PHNlcnZpY2UgeG1sbnM9Imh0dHA6Ly9QdW50b0FjY2Vzc29QU1Auc3Bjb29wLmdvdi5pdC9HZW5lcmFsU2VydmljZSIgeHNpOnNjaGVtYUxvY2F0aW9uPSJodHRwOi8vUHVudG9BY2Nlc3NvUFNQLnNwY29vcC5nb3YuaXQvR2VuZXJhbFNlcnZpY2Ugc2NoZW1hLnhzZCIgeG1sbnM6eHNpPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxL1hNTFNjaGVtYS1pbnN0YW5jZSI+CiAgPGRlc2NyaXB0aW9uPmRvbmF0aW9uPC9kZXNjcmlwdGlvbj4KPC9zZXJ2aWNlPg==";
    bundle.responseToCheck = await paDemandPaymentNotice(buildPaDemandPaymentNoticeRequest(bundle));
}

// ebollo example service
async function sendValidDemandPaymentNoticeRequest(bundle) {
    bundle.serviceData = "PG1hcmNhRGFCb2xsbyB4bWxucz0iaHR0cDovL3d3dy5hZ2VuemlhZW50cmF0ZS5nb3YuaXQvMjAxNC9NYXJjYURhQm9sbG8iPgogIDxhbW91bnQ+MTYuMDA8L2Ftb3VudD4KICA8ZGVidG9yPgogICAgPHVuaXF1ZUlkZW50aWZpZXI+CiAgICAgIDxlbnRpdHlVbmlxdWVJZGVudGlmaWVyVHlwZT5GPC9lbnRpdHlVbmlxdWVJZGVudGlmaWVyVHlwZT4KICAgICAgPGVudGl0eVVuaXF1ZUlkZW50aWZpZXJWYWx1ZT5SU1NNUkE4NVQxMEg1MDFaPC9lbnRpdHlVbmlxdWVJZGVudGlmaWVyVmFsdWU+CiAgICA8L3VuaXF1ZUlkZW50aWZpZXI+CiAgICA8ZnVsbE5hbWU+TWFyaW8gUm9zc2k8L2Z1bGxOYW1lPgogICAgPGVtYWlsPm1hcmlvLnJvc3NpQGV4YW1wbGUuaXQ8L2VtYWlsPgogIDwvZGVidG9yPgogIDxmaXNjYWxDb2RlPjc3Nzc3Nzc3Nzc3PC9maXNjYWxDb2RlPgogIDxwcm92aW5jZT5NSTwvcHJvdmluY2U+CiAgPGRvY3VtZW50SGFzaD40N0RFUXBqOEhCU2ErL1RJbVcrNUpDZXVRZVJrbTVOTXBKV1pHM2hTdUZVPTwvZG9jdW1lbnRIYXNoPgo8L21hcmNhRGFCb2xsbz4=";
    bundle.responseToCheck = await paDemandPaymentNotice(buildPaDemandPaymentNoticeRequest(bundle));
}


module.exports = {
    setVerticalServiceInfo,
    sendInvalidDemandPaymentNoticeRequest,
    sendValidDemandPaymentNoticeRequest,
}
