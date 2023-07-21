import http from "k6/http";
import { check } from "k6";
import { parseHTML } from "k6/html";
import { SharedArray } from "k6/data";
import exec from "k6/execution";
import { makeidNumber, makeidMix, makeidMixIuv, randomString } from "./modules/helpers.js";
import { getDebtPosition, getpaVerifyPaymentNoticeReqBody, getpaGetPaymentReqBody, getpaSendRTReqBody } from "./modules/data.js";

export let options = JSON.parse(open(__ENV.TEST_TYPE));

// read configuration
const varsArray = new SharedArray("vars", function () {
  return JSON.parse(open(`./${__ENV.VARS}`)).environment;
});
const vars = varsArray[0];

// initialize parameters taken from env
const urlGPDBasePath = `${vars.gpd_host}`;
const urlPaymentsBasePath = `${vars.soap_payments_host}`;
const creditorInstitutionCode = `${vars.id_pa}`;
const idBrokerPA = `${vars.id_broker_pa}`;
const idStation = `${vars.id_station}`;
const service = `${vars.env}`.toLowerCase() === "local" ? "/partner" : "";

const gpdSubscriptionKey = `${__ENV.GPD_SUBSCRIPTION_KEY}`;
const soapSubscriptionKey = `${__ENV.SOAP_SUBSCRIPTION_KEY}`;
const numberOfPositionsToPreload = __ENV.DEBT_POSITION_NUMBER;
const batchSize = 100;

var pdArray = new Array();

export function setup() {
  let tag;
  const numberOfBatch = Math.ceil(numberOfPositionsToPreload / batchSize);

  for (let i = 0; i < numberOfBatch; i++) {
    let batchArrayDebtPosition = new Array();
    let batchArrayVerifyPayment = new Array();
    let batchArrayGetPayment = new Array();

    for (let j = 0; j < batchSize; j++) {
      const iupd = makeidMix(35);
      const iuv_1 = makeidNumber(17);
      const iuv_2 = makeidNumber(17);
      const iuv_3 = makeidNumber(17);
      const due_date = new Date().addDays(30);
      const retention_date = new Date().addDays(90);
      const transfer_id_1 = "1";
      const transfer_id_2 = "2";

      pdArray.push([creditorInstitutionCode, iuv_1]);

      let url = `${urlGPDBasePath}/organizations/${creditorInstitutionCode}/debtpositions?toPublish=true`;
      const gpdParams = {
        headers: {
          "Content-Type": "application/json",
          "Ocp-Apim-Subscription-Key": gpdSubscriptionKey,
        },
      };
      let payload = getDebtPosition(iupd, iuv_1, iuv_2, iuv_3, due_date, retention_date, transfer_id_1, transfer_id_2);
      batchArrayDebtPosition.push(["POST", url, payload, gpdParams]);

      url = `${urlPaymentsBasePath}${service}`;
      let soapParams = {
        responseType: "text",
        headers: {
          "Content-Type": "text/xml",
          SOAPAction: "paVerifyPaymentNotice",
          "Ocp-Apim-Subscription-Key": soapSubscriptionKey
        },
      };
      payload = getpaVerifyPaymentNoticeReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv_1);
      batchArrayVerifyPayment.push(["POST", url, payload, soapParams]);

      url = `${urlPaymentsBasePath}${service}`;
      soapParams = {
        responseType: "text",
        headers: {
          "Content-Type": "text/xml",
          SOAPAction: "paGetPayment",
          "Ocp-Apim-Subscription-Key": soapSubscriptionKey
        },
      };
      payload = getpaGetPaymentReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv_1);
      batchArrayGetPayment.push(["POST", url, payload, soapParams]);
    }

    let responses = http.batch(batchArrayDebtPosition);
    for (let j = 0; j < batchSize; j++) {
      check(responses[j], { "Create and Publish DebtPosition status is 201": (response) => response.status === 201 }, { paymentRequest: "CreateDebtPosition" });
    }

    responses = http.batch(batchArrayVerifyPayment);
    for (let j = 0; j < batchSize; j++) {
      check(responses[j], { "VerifyPayment status is 200 and outcome is OK": (response) => response.status === 200 && parseHTML(response.body).find("outcome").get(0).textContent() === "OK" }, { paymentRequest: "VerifyPayment" });
    }

    responses = http.batch(batchArrayGetPayment);
    for (let j = 0; j < batchSize; j++) {
      check(responses[j], { "ActivatePayment status is 200 and outcome is OK": (response) => response.status === 200 && parseHTML(response.body).find("outcome").get(0).textContent() === "OK" }, { paymentRequest: "GetPayment" });
    }
  }

  return { pds: pdArray };
}

export default function (data) {
  const tag = { paymentRequest: "SendRT" };

  const receiptId = makeidMix(33);

  let idx = exec.scenario.iterationInInstance;
  let pair = data.pds[idx];

  const creditorInstitutionCode = pair[0];
  const iuv = pair[1];

  const soapParams = {
    responseType: "text",
    headers: {
      "Content-Type": "text/xml",
      SOAPAction: "paSendRT",
      "Ocp-Apim-Subscription-Key": soapSubscriptionKey
    },
  };
  const payload = getpaSendRTReqBody(creditorInstitutionCode, idBrokerPA, idStation, receiptId, iuv);
  const url = `${urlPaymentsBasePath}${service}`;

  const response = http.post(url, payload, soapParams);

  check(response, { "SendRT status is 200 and outcome is OK": (response) => response.status === 200 && parseHTML(response.body).find("outcome").get(0).textContent() === "OK" }, tag);
}
