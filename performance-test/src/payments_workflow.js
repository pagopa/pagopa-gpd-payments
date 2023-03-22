import http from "k6/http";
import { check } from "k6";
import { parseHTML } from "k6/html";
import { SharedArray } from "k6/data";
import { makeidNumber, makeidMix, randomString } from "./modules/helpers.js";
import { getDebtPosition, getpaGetPaymentReqBody, getpaSendRTReqBody, getpaVerifyPaymentNoticeReqBody } from "./modules/data.js";

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

export default function () {
  // initialize constant values for this execution
  const iupd = makeidMix(35);
  const iuv_1 = makeidNumber(17);
  const iuv_2 = makeidNumber(17);
  const iuv_3 = makeidNumber(17);
  const due_date = new Date().addDays(30);
  const retention_date = new Date().addDays(90);
  const transfer_id_1 = "1";
  const transfer_id_2 = "2";
  const receiptId = makeidMix(33);

  // Create new debt position (no validity date).
  var tag = {
    gpdMethod: "CreateDebtPosition",
  };

  // defining URL, body and headers related to the CreateDebtPosition call
  var url = `${urlGPDBasePath}/organizations/${creditorInstitutionCode}/debtpositions`;
  var gpdParams = {
    headers: {
      "Content-Type": "application/json",
      "Ocp-Apim-Subscription-Key": gpdSubscriptionKey,
    },
  };


  var payload = getDebtPosition(iupd, iuv_1, iuv_2, iuv_3, due_date, retention_date, transfer_id_1, transfer_id_2);

  // execute the call and check the response
  var response = http.post(url, payload, gpdParams);
  console.log(
    "CreateDebtPosition call - creditor_institution_code = " +
      creditorInstitutionCode +
      ", Status = " +
      response.status +
      " \n\t[URL: " +
      url +
      "]"
  );
  check(
    response,
    {
      "CreateDebtPosition status is 201": (response) => response.status === 201,
    },
    tag
  );

  // if the debt position has been correctly created => publish
  if (response.status === 201) {
    // Publish the debt position.
    tag = {
      gpdMethod: "PublishDebtPosition",
    };

    // defining URL related to the PublishDebtPosition call
    url = `${urlGPDBasePath}/organizations/${creditorInstitutionCode}/debtpositions/${iupd}/publish`;

    // execute the call and check the response
    response = http.post(url, JSON.stringify(""), gpdParams);
    console.log(
      "PublishDebtPosition call - creditor_institution_code = " +
        creditorInstitutionCode +
        ", iupd = " +
        iupd +
        ", Status = " +
        response.status +
        " \n\t[URL: " +
        url +
        "]"
    );
    check(
      response,
      {
        "PublishDebtPosition status is 200": (response) =>
          response.status === 200,
      },
      tag
    );

    // if the debt position has been correctly published => verify payment
    if (response.status === 200) {
      // Verify Payment.
      tag = { paymentRequest: "VerifyPayment" };

      // defining URL, body and headers related to the VerifyPayment call
      url = `${urlPaymentsBasePath}${service}`;
      var soapParams = {
        responseType: "text",
        headers: {
          "Content-Type": "text/xml",
          SOAPAction: "paVerifyPaymentNotice",
        },
      };

      payload = getpaVerifyPaymentNoticeReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv_1);

      // execute the call and check the response
      response = http.post(url, payload, soapParams);
      console.log(
        "VerifyPayment req - creditor_institution_code = " +
          creditorInstitutionCode +
          ", iuv = " +
          iuv_1 +
          ", Status = " +
          response.status +
          " \n\t[URL: " +
          url +
          "]"
      );
      if (response.status != 200 && response.status != 504) {
        console.error(
          "-> VerifyPayment req - creditor_institution_code = " +
            creditorInstitutionCode +
            ", iuv = " +
            iuv_1 +
            ", Status = " +
            response.status +
            ", Body=" +
            response.body
        );
      }
      check(
        response,
        {
          "VerifyPayment status is 200 and outcome is OK": (response) =>
            response.status === 200 &&
            parseHTML(response.body).find("outcome").get(0).textContent() ===
              "OK",
        },
        tag
      );

      // if the verify payment has OK => activate payment
      if (
        response.status === 200 &&
        parseHTML(response.body).find("outcome").get(0).textContent() === "OK"
      ) {
        // Activate Payment.
        tag = {
          paymentRequest: "GetPayment",
        };

        // defining URL, body and headers related to the GetPayment call
        url = `${urlPaymentsBasePath}${service}`;
        soapParams = {
          responseType: "text",
          headers: {
            "Content-Type": "text/xml",
            SOAPAction: "paGetPayment",
          },
        };

        payload = getpaGetPaymentReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv_1);

        // execute the call and check the response
        response = http.post(url, payload, soapParams);
        console.log(
          "GetPayment req - creditor_institution_code = " +
            creditorInstitutionCode +
            ", iuv = " +
            iuv_1 +
            ", Status = " +
            response.status +
            +" \n\t[URL: " +
            url +
            "]"
        );
        if (response.status != 200 && response.status != 504) {
          console.error(
            "-> GetPayment req - creditor_institution_code = " +
              creditorInstitutionCode +
              ", iuv = " +
              iuv_1 +
              ", Status = " +
              response.status +
              ", Body=" +
              response.body
          );
        }
        check(
          response,
          {
            "ActivatePayment status is 200 and outcome is OK": (response) =>
              response.status === 200 &&
              parseHTML(response.body).find("outcome").get(0).textContent() ===
                "OK",
          },
          tag
        );

        // if the activate payment has been OK => send receipt
        if (
          response.status === 200 &&
          parseHTML(response.body).find("outcome").get(0).textContent() === "OK"
        ) {
          // Get details of a specific payment option.
          tag = {
            paymentRequest: "SendRT",
          };

          // defining URL, body and headers related to the SendRT call
          url = `${urlPaymentsBasePath}${service}`;
          soapParams = {
            responseType: "text",
            headers: {
              "Content-Type": "text/xml",
              SOAPAction: "paSendRT",
            },
          };
          payload = getpaSendRTReqBody(creditorInstitutionCode, idBrokerPA, idStation, receiptId, iuv_1);

          // execute the call and check the response
          response = http.post(url, payload, soapParams);
          console.log(
            "SendRT req - creditor_institution_code = " +
              creditorInstitutionCode +
              ", iuv = " +
              iuv_1 +
              ", Status = " +
              response.status +
              " \n\t[URL: " +
              url +
              "]"
          );
          if (response.status != 200 && response.status != 504) {
            console.error(
              "-> SendRT req - creditor_institution_code = " +
                creditorInstitutionCode +
                ", iuv = " +
                iuv_1 +
                ", Status = " +
                response.status +
                ", Body=" +
                response.body
            );
          }
          check(
            response,
            {
              "SendRT status is 200 and outcome is OK": (response) =>
                response.status === 200 &&
                parseHTML(response.body)
                  .find("outcome")
                  .get(0)
                  .textContent() === "OK",
            },
            tag
          );
        }
      }
    }
  }
}
