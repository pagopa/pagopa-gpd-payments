import http from "k6/http";
import { check } from "k6";
import { parseHTML } from "k6/html";
import { SharedArray } from "k6/data";
import exec from "k6/execution";
import { makeidNumber, makeidMix, randomString } from "./modules/helpers.js";
import { getDebtPosition, getpaVerifyPaymentNoticeReqBody } from "./modules/data.js";

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
const numberOfPositionsToPreload = __ENV.DEBT_POSITION_NUMBER;

var pdArray = new Array();

export function setup() {
  let tag;

  for (let i = 0; i < numberOfPositionsToPreload; i++) {
    const iupd = makeidMix(35);
    const iuv_1 = makeidNumber(17);
    const iuv_2 = makeidNumber(17);
    const iuv_3 = makeidNumber(17);
    const due_date = new Date().addDays(30);
    const retention_date = new Date().addDays(90);
    const transfer_id_1 = "1";
    const transfer_id_2 = "2";

    tag = { paymentRequest: "CreateDebtPosition" };
    var url = `${urlGPDBasePath}/organizations/${creditorInstitutionCode}/debtpositions?toPublish=true`;
    var payload = getDebtPosition(iupd, iuv_1, iuv_2, iuv_3, due_date, retention_date, transfer_id_1, transfer_id_2);
    var gpdParams = {
      headers: {
        "Content-Type": "application/json",
        "Ocp-Apim-Subscription-Key": gpdSubscriptionKey,
      },
    };

    let response = http.post(url, payload, gpdParams);
    check(
      response,
      {
        "Create and Publish DebtPosition status is 201": (response) => response.status === 201,
      },
      tag
    );

    pdArray.push([creditorInstitutionCode, iuv_1]);
  }

  return { pds: pdArray };
}

export default function (data) {
  const tag = { paymentRequest: "VerifyPayment" };

  let idx = exec.instance.vusActive * exec.vu.iterationInScenario + exec.vu.idInInstance;
  let pair = data.pds[idx];

  const creditorInstitutionCode = pair[0];
  const iuv = pair[1];

  const url = `${urlPaymentsBasePath}${service}`;
  var soapParams = {
    responseType: "text",
    headers: {
      "Content-Type": "text/xml",
      SOAPAction: "paVerifyPaymentNotice",
    },
  };
  const payload = getpaVerifyPaymentNoticeReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv);
  const response = http.post(url, payload, soapParams);

  check(
    response,
    {
      "VerifyPayment status is 200 and outcome is OK": (response) => response.status === 200 && parseHTML(response.body).find("outcome").get(0).textContent() === "OK",
    },
    tag
  );
}
