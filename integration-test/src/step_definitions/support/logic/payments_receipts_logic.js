const assert = require("assert");
const {
    getOrganizationReceipts,
    getReceiptByIuv
} = require("../clients/payments_receipts_client");

async function sendGetOrganizationReceiptsRequest(bundle) {
    bundle.responseToCheck = await getOrganizationReceipts(
        bundle.organizationCode,
        `?pageNum=0&pageSize=20`
    );
}

async function sendGetOrganizationReceiptsFilteredByIuvRequest(bundle) {
    const iuv = bundle.debtPosition.iuv1;
    const segregationCode = iuv.substring(0, 2);

    bundle.responseToCheck = await getOrganizationReceipts(
        bundle.organizationCode,
        `?pageNum=0&pageSize=20&debtorOrIuv=${iuv}&segregationCodes=${segregationCode}`
    );
}

async function sendGetReceiptByIuvRequest(bundle) {
    const iuv = bundle.debtPosition.iuv1;
    const segregationCode = iuv.substring(0, 2);

    bundle.responseToCheck = await getReceiptByIuv(
        bundle.organizationCode,
        iuv,
        `?segregationCodes=${segregationCode}`
    );
}

function assertReceiptsResponseContainsIuv(bundle) {
    assert.strictEqual(bundle.responseToCheck.status, 200);

    const body = bundle.responseToCheck.data;
    assert.ok(body);
    assert.ok(Array.isArray(body.results), `Unexpected body: ${JSON.stringify(body)}`);
    assert.ok(body.results.length > 0, `No receipts found: ${JSON.stringify(body)}`);

    const found = body.results.some(r => r.iuv === bundle.debtPosition.iuv1);
    assert.ok(found, `IUV ${bundle.debtPosition.iuv1} not found in results: ${JSON.stringify(body)}`);
}

function assertReceiptDetailContainsIuv(bundle) {
    assert.strictEqual(bundle.responseToCheck.status, 200);
    assert.match(bundle.responseToCheck.data, new RegExp(bundle.debtPosition.iuv1));
}

// Generic list endpoint smoke check: validates response structure only.
function assertReceiptsResponseContainsResults(bundle) {
    const data = bundle.responseToCheck?.data;

    assert.ok(data, "Missing response body");

    const parsed = typeof data === "string" ? JSON.parse(data) : data;

    assert.ok(parsed.results !== undefined, `results field not found in response: ${JSON.stringify(parsed)}`);
    assert.ok(Array.isArray(parsed.results), `results is not an array: ${JSON.stringify(parsed)}`);
    assert.ok(parsed.currentPageNumber !== undefined, `currentPageNumber not found in response: ${JSON.stringify(parsed)}`);
    assert.ok(parsed.totalPages !== undefined, `totalPages not found in response: ${JSON.stringify(parsed)}`);
    assert.ok(parsed.length !== undefined, `length not found in response: ${JSON.stringify(parsed)}`);
}

function assertReceiptsResponseIsValid(bundle) {
    const response = bundle.responseToCheck;

    assert.ok(response?.data, "Missing response body");
    assert.ok(
        Object.prototype.hasOwnProperty.call(response.data, "currentPageNumber"),
        `Missing currentPageNumber in response: ${JSON.stringify(response.data)}`
    );
    assert.ok(
        Object.prototype.hasOwnProperty.call(response.data, "length"),
        `Missing length in response: ${JSON.stringify(response.data)}`
    );
    assert.ok(
        Object.prototype.hasOwnProperty.call(response.data, "totalPages"),
        `Missing totalPages in response: ${JSON.stringify(response.data)}`
    );
    assert.ok(
        Array.isArray(response.data.results),
        `results is not an array: ${JSON.stringify(response.data)}`
    );
}

module.exports = {
    sendGetOrganizationReceiptsRequest,
    sendGetOrganizationReceiptsFilteredByIuvRequest,
    sendGetReceiptByIuvRequest,
    assertReceiptsResponseContainsIuv,
    assertReceiptDetailContainsIuv,
	assertReceiptsResponseContainsResults,
	assertReceiptsResponseIsValid
};