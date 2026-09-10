const assert = require("assert");
const { apiConfigHealthCheck } = require("../clients/api_config_client");
const { gpdHealthCheck } = require("../clients/gpd_client");
const { verticalServiceHealthCheck } = require("../clients/vertical_service_client");
const { healthCheck } = require("../clients/payments_client");


async function executeHealthCheckForGPDPayments() {
    const response = await healthCheck();
    assert.strictEqual(response.status, 200);
}

async function executeHealthCheckForVerticalService() {
    const response = await verticalServiceHealthCheck();
    assert.strictEqual(response.status, 200);
}

async function executeHealthCheckForGPD() {
    const response = await gpdHealthCheck();
    assert.strictEqual(response.status, 200);
}

async function executeHealthCheckForAPIConfig() {
    const response = await apiConfigHealthCheck();
    assert.strictEqual(response.status, 200);
}

module.exports = {
    executeHealthCheckForAPIConfig,
    executeHealthCheckForGPD,
    executeHealthCheckForGPDPayments,
    executeHealthCheckForVerticalService
}