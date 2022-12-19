import http from 'k6/http';
import { check } from 'k6';
import { parseHTML } from "k6/html";
import { SharedArray } from 'k6/data';
// This will export to HTML as filename "result.html" AND also stdout using the text summary
import { htmlReport } from "https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

export let options = JSON.parse(open(__ENV.TEST_TYPE));

// read configuration
const varsArray = new SharedArray('vars', function() {
	return JSON.parse(open(`./${__ENV.VARS}`)).environment;
});
const vars = varsArray[0];

// initialize parameters taken from env
const paymentsHost = `${vars.soap_payments_host}`;
const gpsHost = `${vars.gps_host}`;
const donation_host = `${vars.donation_host}`;
const creditorInstitutionCode = `${vars.id_pa}`
const idBrokerPA = `${vars.id_broker_pa}`
const idStation = `${vars.id_station}`
const serviceId = `${vars.service_id}`

const subscriptionKey = `${__ENV.API_SUBSCRIPTION_KEY}`


export function setup() {
    // 2. setup code (once)
    // The setup code runs, setting up the test environment (optional) and generating data
    // used to reuse code for the same VU
    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Ocp-Apim-Subscription-Key': subscriptionKey
        }
    };
    let payload = JSON.stringify({
        "id": serviceId,
        "name": "DonationpagoPAservice",
        "description": "DonationpagoPAservice",
        "transferCategory": "tassonomia-1",
        "status": "ENABLED",
        "endpoint": donation_host,
        "basePath": "/donations/paymentoptions",
        "properties": [
            {
                "name": "amount",
                "type": "NUMBER",
                "required": true
            },
            {
                "name": "description",
                "type": "STRING"
            }
        ]
    })
    let response = http.post(gpsHost + "/services", payload, params);
    console.log(`Inserting service with id ${serviceId}. Status: ${response.status}`);
    check(response, {
        'create service status is 201': () => response.status === 201,
    })

    payload = JSON.stringify({
        "companyName": "Comune di Milano",
        "enrollments": [
            {
                "serviceId": serviceId,
                "iban": "IT00000000000000001",
                "officeName": "Ufficio Tributi",
                "segregationCode": "77",
                "remittanceInformation": "causale di pagamento"
            }
        ]
    })
    response = http.post(gpsHost + "/organizations/" + creditorInstitutionCode, payload, params);
    console.log(`Inserting organization with id ${creditorInstitutionCode}. Status: ${response.status}`);
    check(response, {
        'create organization status is 201': () => response.status === 201,
    })
}


export default function () {

    let url = paymentsHost;
    let payload = `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                        <soapenv:Body>
                            <pafn:paDemandPaymentNoticeRequest>
                                <idPA>${creditorInstitutionCode}</idPA>
                                <idBrokerPA>${idBrokerPA}</idBrokerPA>
                                <idStation>${idStation}</idStation>
                                <idServizio>${serviceId}</idServizio>
                                <datiSpecificiServizioRequest>PHNlcnZpY2UgeG1sbnM9Imh0dHA6Ly9QdW50b0FjY2Vzc29QU1Auc3Bjb29wLmdvdi5pdC9HZW5lcmFsU2VydmljZSIgeHNpOnNjaGVtYUxvY2F0aW9uPSJodHRwOi8vUHVudG9BY2Nlc3NvUFNQLnNwY29vcC5nb3YuaXQvR2VuZXJhbFNlcnZpY2Ugc2NoZW1hLnhzZCIgeG1sbnM6eHNpPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxL1hNTFNjaGVtYS1pbnN0YW5jZSI+CiAgPGFtb3VudD4xMDA8L2Ftb3VudD4KICA8ZGVzY3JpcHRpb24+ZG9uYXRpb248L2Rlc2NyaXB0aW9uPgo8L3NlcnZpY2U+</datiSpecificiServizioRequest>
                            </pafn:paDemandPaymentNoticeRequest>
                        </soapenv:Body>
                    </soapenv:Envelope>`;

    let params = {
        headers: {
            'Content-Type': 'text/xml',
            'SOAPAction': 'paDemandPaymentNotice'
        },
    };
    let response = http.post(url, payload, params);
    let tag = {
        paymentRequest: "DemandPaymentNotice",
    };
    check(response, {
        'DemandPaymentNotice status is 200': () => response.status === 200 && (parseHTML(response.body)).find('outcome').get(0).textContent() === 'OK',
    }, tag)

}

export function teardown() {
    let response = http.del(gpsHost + "/services/" + serviceId)
    check(response, {
        'delete service status is 200': () => response.status === 200,
    })

    response = http.del(gpsHost + "/organizations/" + creditorInstitutionCode)
    check(response, {
        'delete organization status is 200': () => response.status === 200,
    })
}


export function handleSummary(data) {
    return {
        "result.html": htmlReport(data),
        stdout: textSummary(data, { indent: " ", enableColors: true }),
    };
}
