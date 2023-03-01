const assert = require("assert");
const { apiConfigHealthCheck } = require("../clients/api_config_client");
const { donationHealthCheck } = require("../clients/donation_service_client");
const { gpdHealthCheck } = require("../clients/gpd_client");
const { gpsHealthCheck } = require("../clients/gps_client");
const { iuvGenHealthCheck } = require("../clients/iuv_generator_client");
const { healthCheck } = require("../clients/payments_client");


async function executeHealthCheckForGPDPayments() {
    const response = await healthCheck();
    assert.strictEqual(response.status, 200);
}

async function executeHealthCheckForGPS() {
    const response = await gpsHealthCheck();
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

async function executeHealthCheckForIUVGenerator() {
    const response = await iuvGenHealthCheck();
    assert.strictEqual(response.status, 200);
}

async function executeHealthCheckForDonations() {
    const response = await donationHealthCheck();
    assert.strictEqual(response.status, 200);
}

module.exports = {
    executeHealthCheckForAPIConfig,
    executeHealthCheckForDonations,
    executeHealthCheckForGPD,
    executeHealthCheckForGPDPayments,
    executeHealthCheckForGPS,
    executeHealthCheckForIUVGenerator
}