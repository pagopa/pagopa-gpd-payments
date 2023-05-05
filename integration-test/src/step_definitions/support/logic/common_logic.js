const assert = require("assert");
const { executeGPSDataDeletion } = require("./gps_logic");

async function assertAmount(bundle, amount) {
    assertOutcome(bundle, "OK");
    assert.match(bundle.responseToCheck.data, new RegExp(`<amount>${amount}</amount>`, "g"));
}

async function assertFaultCode(bundle, fault) {
    assertOutcome(bundle, "KO");
    assert.match(bundle.responseToCheck.data, new RegExp(`<faultCode>${fault}</faultCode>`, "g"));
}

async function assertOutcome(bundle, outcome) {
    assert.match(bundle.responseToCheck.data, new RegExp(`<outcome>${outcome}</outcome>`, "g"));    
}

async function assertStatusCode(bundle, statusCode) {
    assert.strictEqual(bundle.responseToCheck.status, statusCode);
}

async function executeAfterAllStep(gpsSessionBundle) {
    if (gpsSessionBundle.isExecuting) {
        await executeGPSDataDeletion(gpsSessionBundle);
    }
}

module.exports = {
    assertAmount,
    assertFaultCode,
    assertOutcome,
    assertStatusCode,
    executeAfterAllStep,
}
