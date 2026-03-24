const { get } = require("../utility/axios_common");

const paymentsReceiptsHost = process.env.payments_receipts_host;

if (!paymentsReceiptsHost) {
    throw new Error("Missing required environment variable: payments_receipts_host");
}

function getOrganizationReceipts(orgFiscalCode, queryParams = "") {
    return get(
        `${paymentsReceiptsHost}/payments/${orgFiscalCode}/receipts${queryParams}`,
        {
            headers: {
                "Ocp-Apim-Subscription-Key": process.env.SUBKEY
            }
        }
    );
}

function getReceiptByIuv(orgFiscalCode, iuv, queryParams = "") {
    return get(
        `${paymentsReceiptsHost}/payments/${orgFiscalCode}/receipts/${iuv}${queryParams}`,
        {
            headers: {
                "Ocp-Apim-Subscription-Key": process.env.SUBKEY
            }
        }
    );
}

module.exports = {
    getOrganizationReceipts,
    getReceiptByIuv
};