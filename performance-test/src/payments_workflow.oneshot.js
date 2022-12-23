import http from 'k6/http';
import { check, sleep } from 'k6';
import { parseHTML } from "k6/html";
import { SharedArray } from 'k6/data';
import Papa from "./modules/papaparse.min.js";

export let options = JSON.parse(open(__ENV.TEST_TYPE));

// read configuration
const varsArray = new SharedArray('vars', function() {
	return JSON.parse(open(`./${__ENV.VARS}`)).environment;
});
const vars = varsArray[0];

const filename = `${vars.oneshot_filename}`;
const data = open(filename);

const urlPaymentsBasePath = `${vars.soap_payments_host}`
const idBrokerPA = `${vars.id_broker_pa}`
const idStation = `${vars.id_station}`
const service = `${vars.env}`.toLowerCase() === "local" ? "partner" : ""

const subscriptionKey = `${__ENV.API_SUBSCRIPTION_KEY}`

export function setup() {
    return Papa.parse(data, {header: true});
}

export default function (results) {
    for (let row of results.data) {
        if (row.hasOwnProperty("pa_id_istat")) {
            callPayments(row.pa_id_istat, row.payment_notice_number, row.amount, `payment_token_${row.id}`, row.debtor_name, row.debtor_email, row.debtor_id_fiscal_code);
        }
    }

}

function callPayments(creditor_institution_code, notice_number, amount, receiptId, debtorName, debtorMail, fiscalcode) {
    // Verify Payment.
    let tag = {
        paymentRequest: "VerifyPayment",
    };

    let url = `${urlPaymentsBasePath}/${service}`;

    let payload = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:nod="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                       <soapenv:Header />
                        <soapenv:Body>
                            <nod:paVerifyPaymentNoticeReq>
                                <idPA>${creditor_institution_code}</idPA>
                                <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                <idStation>${idStation}</idStation>
                                <qrCode>
                                    <fiscalCode>${creditor_institution_code}</fiscalCode>
                                    <noticeNumber>${notice_number}</noticeNumber>
                                </qrCode>
                            </nod:paVerifyPaymentNoticeReq>
                        </soapenv:Body>
                    </soapenv:Envelope>`;


    let soapParams = {
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paVerifyPaymentNotice'
        },
    };
    let r = http.post(url, payload, soapParams);
    if (r.status != 200 && r.status != 504) {
        console.error("-> VerifyPayment req - creditor_institution_code = " + creditor_institution_code + ", iuv = " + notice_number + ", Status = " + r.status + ", Body=" + r.body);
    }

    check(r, {
        'VerifyPayment status is 200 and outcome is OK': (r) => r.status === 200 && (parseHTML(r.body)).find('outcome').get(0).textContent() === 'OK',
    }, tag);


    // if the verify payment has OK => activate payment
    if (r.status === 200 && parseHTML(r.body).find('outcome').get(0).textContent() === 'OK') {
        sleep(4);
        // Activate Payment.
        tag = {
            paymentRequest: "GetPayment",
        };

        url = `${urlPaymentsBasePath}/${service}`;

        payload = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                        <soapenv:Header />
                        <soapenv:Body>
                            <pafn:paGetPaymentReq>
                                <idPA>${creditor_institution_code}</idPA>
                                <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                <idStation>${idStation}</idStation>
                                <qrCode>
                                    <fiscalCode>${creditor_institution_code}</fiscalCode>
                                    <noticeNumber>3${notice_number}</noticeNumber>
                                </qrCode>
                                <amount>${amount}</amount>
                            </pafn:paGetPaymentReq>
                        </soapenv:Body>
                    </soapenv:Envelope>`;

        soapParams = {
            headers: {
                'Content-Type': 'text/xml',
                'SOAPAction': 'paGetPayment'
            },
        };

        r = http.post(url, payload, soapParams);

        console.log("GetPayment req - creditor_institution_code = " + creditor_institution_code + ", iuv = " + notice_number + ", Status = " + r.status);
        if (r.status != 200 && r.status != 504) {
            console.error("-> GetPayment req - creditor_institution_code = " + creditor_institution_code + ", iuv = " + notice_number + ", Status = " + r.status + ", Body=" + r.body);
        }

        check(r, {
            'ActivatePayment status is 200 and outcome is OK': (r) => r.status === 200 && (parseHTML(r.body)).find('outcome').get(0).textContent() === 'OK',
        }, tag);

        // if the activate payment has been OK => send receipt
        if (r.status === 200 && parseHTML(r.body).find('outcome').get(0).textContent() === 'OK') {
            sleep(8);
            // Get details of a specific payment option.
            tag = {
                paymentRequest: "SendRT",
            };

            url = `${urlPaymentsBasePath}/${service}`;

            payload = `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                            <soapenv:Body>
                                <pafn:paSendRTReq>
                                    <idPA>${creditor_institution_code}</idPA>
                                    <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                    <idStation>${idStation}</idStation>
                                    <receipt>
                                        <receiptId>${receiptId}</receiptId>
                                        <noticeNumber>3${notice_number}</noticeNumber>
                                        <fiscalCode>${creditor_institution_code}</fiscalCode>
                                        <outcome>OK</outcome>
                                        <creditorReferenceId>${notice_number}</creditorReferenceId>
                                        <paymentAmount>${amount}</paymentAmount>
                                        <description>test</description>
                                        <companyName>company Name</companyName>
                                        <officeName>office Name</officeName>
                                        <debtor>
                                            <uniqueIdentifier>
                                                <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                                <entityUniqueIdentifierValue>${fiscalcode}</entityUniqueIdentifierValue>
                                            </uniqueIdentifier>
                                            <fullName>${debtorName}</fullName>
                                            <streetName>via roma</streetName>
                                            <civicNumber>1</civicNumber>
                                            <postalCode>00111</postalCode>
                                            <city>rome</city>
                                            <stateProvinceRegion>MI</stateProvinceRegion>
                                            <country>IT</country>
                                            <e-mail>${debtorMail}</e-mail>
                                        </debtor>
                                        <transferList>
                                            <transfer>
                                                <idTransfer>1</idTransfer>
                                                <transferAmount>${amount}</transferAmount>
                                                <fiscalCodePA>${creditor_institution_code}</fiscalCodePA>
                                                <IBAN>IT23X0000100001000000000999</IBAN>
                                                <remittanceInformation>remittanceInformation1</remittanceInformation>
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
                                    </receipt>
                                </pafn:paSendRTReq>
                            </soapenv:Body>
                        </soapenv:Envelope>`;

            soapParams = {
                headers: {
                    'Content-Type': 'text/xml',
                    'SOAPAction': 'paSendRT'
                },
            };

            r = http.post(url, payload, soapParams);

            console.log("SendRT req - creditor_institution_code = " + creditor_institution_code + ", iuv = " + notice_number + ", Status = " + r.status);
            if (r.status != 200 && r.status != 504) {
                console.error("-> SendRT req - creditor_institution_code = " + creditor_institution_code + ", iuv = " + notice_number + ", Status = " + r.status + ", Body=" + r.body);
            }

            check(r, {
                'SendRT status is 200 and outcome is OK': (r) => r.status === 200 && (parseHTML(r.body)).find('outcome').get(0).textContent() === 'OK',
            }, tag);

        }
    }
}

