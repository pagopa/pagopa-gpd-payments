import http from "k6/http";
import { check } from "k6";
import { parseHTML } from "k6/html";
import { sleep } from "k6";
import { SharedArray } from "k6/data";
import { makeidNumber, makeidMix, randomString } from "./modules/helpers.js";

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
  var payload = JSON.stringify({
    iupd: iupd,
    type: "F",
    fiscalCode: "JHNDOE00A01F205N",
    fullName: "John Doe",
    streetName: "streetName",
    civicNumber: "11",
    postalCode: "00100",
    city: "city",
    province: "RM",
    region: "RM",
    country: "IT",
    email: "lorem@lorem.com",
    phone: "333-123456789",
    companyName: "companyName",
    officeName: "officeName",
    paymentOption: [
      {
        iuv: iuv_1,
        amount: 10000,
        description: "Canone Unico Patrimoniale - CORPORATE opt 1 FINAL",
        isPartialPayment: false,
        dueDate: due_date,
        retentionDate: retention_date,
        fee: 0,
        transfer: [
          {
            idTransfer: transfer_id_1,
            amount: 9000,
            remittanceInformation: "remittanceInformation 1",
            category: "9/0101108TS/",
            iban: "IT0000000000000000000000000",
          },
          {
            idTransfer: transfer_id_2,
            amount: 1000,
            remittanceInformation: "remittanceInformation 2",
            category: "9/0101108TS/",
            iban: "IT0000000000000000000000000",
          },
        ],
      },
      {
        iuv: iuv_2,
        amount: 5000,
        description: "Canone Unico Patrimoniale - CORPORATE opt 2 NOT FINAL",
        isPartialPayment: true,
        dueDate: due_date,
        retentionDate: retention_date,
        fee: 0,
        transfer: [
          {
            idTransfer: transfer_id_1,
            amount: 4000,
            remittanceInformation: "remittanceInformation 1",
            category: "9/0101108TS/",
            iban: "IT0000000000000000000000000",
          },
          {
            idTransfer: transfer_id_2,
            amount: 1000,
            remittanceInformation: "remittanceInformation 2",
            category: "9/0101108TS/",
            iban: "IT0000000000000000000000000",
          },
        ],
      },
      {
        iuv: iuv_3,
        amount: 5000,
        description: "Canone Unico Patrimoniale - CORPORATE opt 3 NOT FINAL",
        isPartialPayment: true,
        dueDate: due_date,
        retentionDate: retention_date,
        fee: 0,
        transfer: [
          {
            idTransfer: transfer_id_1,
            amount: 4000,
            remittanceInformation: "remittanceInformation 1",
            category: "9/0101108TS/",
            iban: "IT0000000000000000000000000",
          },
          {
            idTransfer: transfer_id_2,
            amount: 1000,
            remittanceInformation: "remittanceInformation 2",
            category: "9/0101108TS/",
            iban: "IT0000000000000000000000000",
          },
        ],
      },
    ],
  });

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
      sleep(2);
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
      payload = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:nod="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                            <soapenv:Header />
                            <soapenv:Body>
                                <nod:paVerifyPaymentNoticeReq>
                                    <idPA>${creditorInstitutionCode}</idPA>
                                    <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                    <idStation>${idStation}</idStation>
                                    <qrCode>
                                        <fiscalCode>${creditorInstitutionCode}</fiscalCode>
                                        <noticeNumber>3${iuv_1}</noticeNumber>
                                    </qrCode>
                                </nod:paVerifyPaymentNoticeReq>
                            </soapenv:Body>
                        </soapenv:Envelope>`;

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
        sleep(4);
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
        payload = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                                <soapenv:Header />
                                <soapenv:Body>
                                    <pafn:paGetPaymentReq>
                                        <idPA>${creditorInstitutionCode}</idPA>
                                        <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                        <idStation>${idStation}</idStation>
                                        <qrCode>
                                            <fiscalCode>${creditorInstitutionCode}</fiscalCode>
                                            <noticeNumber>3${iuv_1}</noticeNumber>
                                        </qrCode>
                                        <amount>10.00</amount>
                                    </pafn:paGetPaymentReq>
                                </soapenv:Body>
                            </soapenv:Envelope>`;

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
          sleep(8);
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
          payload = `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                                    <soapenv:Body>
                                        <pafn:paSendRTReq>
                                            <idPA>${creditorInstitutionCode}</idPA>
                                            <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                            <idStation>${idStation}</idStation>
                                            <receipt>
                                                <receiptId>${receiptId}</receiptId>
                                                <noticeNumber>3${iuv_1}</noticeNumber>
                                                <fiscalCode>${creditorInstitutionCode}</fiscalCode>
                                                <outcome>OK</outcome>
                                                <creditorReferenceId>${iuv_1}</creditorReferenceId>
                                                <paymentAmount>30.00</paymentAmount>
                                                <description>test</description>
                                                <companyName>company EC</companyName>
                                                <officeName>office EC</officeName>
                                                <debtor>
                                                    <uniqueIdentifier>
                                                        <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                                        <entityUniqueIdentifierValue>JHNDOE00A01F205N</entityUniqueIdentifierValue>
                                                    </uniqueIdentifier>
                                                    <fullName>John Doe</fullName>
                                                    <streetName>street</streetName>
                                                    <civicNumber>12</civicNumber>
                                                    <postalCode>89020</postalCode>
                                                    <city>city</city>
                                                    <stateProvinceRegion>MI</stateProvinceRegion>
                                                    <country>IT</country>
                                                    <e-mail>john.doe@test.it</e-mail>
                                                </debtor>
                                                <transferList>
                                                    <transfer>
                                                        <idTransfer>1</idTransfer>
                                                        <transferAmount>20.00</transferAmount>
                                                        <fiscalCodePA>${creditorInstitutionCode}</fiscalCodePA>
                                                        <IBAN>IT0000000000000000000000000</IBAN>
                                                        <remittanceInformation>remittanceInformation1</remittanceInformation>
                                                        <transferCategory>G</transferCategory>
                                                    </transfer>
                                                    <transfer>
                                                        <idTransfer>2</idTransfer>
                                                        <transferAmount>10.00</transferAmount>
                                                        <fiscalCodePA>${creditorInstitutionCode}</fiscalCodePA>
                                                        <IBAN>IT0000000000000000000000001</IBAN>
                                                        <remittanceInformation>remittanceInformation2</remittanceInformation>
                                                        <transferCategory>G</transferCategory>
                                                    </transfer>
                                                </transferList>
                                                <idPSP>88888888888</idPSP>
                                                <pspFiscalCode>88888888888</pspFiscalCode>
                                                <pspPartitaIVA>88888888888</pspPartitaIVA>
                                                <PSPCompanyName>PSP name</PSPCompanyName>
                                                <idChannel>88888888888_01</idChannel>
                                                <channelDescription>app</channelDescription>
                                                <payer>
                                                    <uniqueIdentifier>
                                                        <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                                        <entityUniqueIdentifierValue>JHNDOE00A01F205N</entityUniqueIdentifierValue>
                                                    </uniqueIdentifier>
                                                    <fullName>John Doe</fullName>
                                                    <streetName>street</streetName>
                                                    <civicNumber>12</civicNumber>
                                                    <postalCode>89020</postalCode>
                                                    <city>city</city>
                                                    <stateProvinceRegion>MI</stateProvinceRegion>
                                                    <country>IT</country>
                                                    <e-mail>john.doe@test.it</e-mail>
                                                </payer>
                                                <paymentMethod>creditCard</paymentMethod>
                                                <fee>2.00</fee>
                                                <paymentDateTime>2021-10-01T17:48:22</paymentDateTime>
                                                <applicationDate>2021-10-01</applicationDate>
                                                <transferDate>2021-10-02</transferDate>
                                                <metadata>
                                                    <mapEntry>
                                                        <key>keytest</key>
                                                        <value>1</value>
                                                    </mapEntry>
                                                </metadata>
                                            </receipt>
                                        </pafn:paSendRTReq>
                                    </soapenv:Body>
                                </soapenv:Envelope>`;

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
