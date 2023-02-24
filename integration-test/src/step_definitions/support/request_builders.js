const { makeidNumber, makeidMix, addDays } = require("./helpers");

/*
function buildGPSServiceCreationRequest(serviceId, donation_host) {
    return {
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
    }
}

function buildGPSOrganizationCreationRequest(serviceId) {
    return {
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
    };
}

function buildValidDemandPaymentNoticeRequest(organizationCode) {
    return `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>
            <pafn:paDemandPaymentNoticeRequest>
                <idPA>${organizationCode}</idPA>
                <idBrokerPA>15376371009</idBrokerPA>
                <idStation>15376371009_01</idStation>
                <idServizio>12345</idServizio>
                <datiSpecificiServizioRequest>PHNlcnZpY2UgeG1sbnM9Imh0dHA6Ly9QdW50b0FjY2Vzc29QU1Auc3Bjb29wLmdvdi5pdC9HZW5lcmFsU2VydmljZSIgeHNpOnNjaGVtYUxvY2F0aW9uPSJodHRwOi8vUHVudG9BY2Nlc3NvUFNQLnNwY29vcC5nb3YuaXQvR2VuZXJhbFNlcnZpY2Ugc2NoZW1hLnhzZCIgeG1sbnM6eHNpPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxL1hNTFNjaGVtYS1pbnN0YW5jZSI+CiAgPGFtb3VudD4xMDAuMDA8L2Ftb3VudD4KICA8ZGVzY3JpcHRpb24+ZG9uYXRpb248L2Rlc2NyaXB0aW9uPgo8L3NlcnZpY2U+</datiSpecificiServizioRequest>
            </pafn:paDemandPaymentNoticeRequest>
        </soapenv:Body>
    </soapenv:Envelope>`;
}


function buildInvalidDemandPaymentNoticeRequest(organizationCode) {
    return `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>
            <pafn:paDemandPaymentNoticeRequest>
                <idPA>${organizationCode}</idPA>
                <idBrokerPA>15376371009</idBrokerPA>
                <idStation>15376371009_01</idStation>
                <idServizio>12345</idServizio>
                <datiSpecificiServizioRequest>PHNlcnZpY2UgeG1sbnM9Imh0dHA6Ly9QdW50b0FjY2Vzc29QU1Auc3Bjb29wLmdvdi5pdC9HZW5lcmFsU2VydmljZSIgeHNpOnNjaGVtYUxvY2F0aW9uPSJodHRwOi8vUHVudG9BY2Nlc3NvUFNQLnNwY29vcC5nb3YuaXQvR2VuZXJhbFNlcnZpY2Ugc2NoZW1hLnhzZCIgeG1sbnM6eHNpPSJodHRwOi8vd3d3LnczLm9yZy8yMDAxL1hNTFNjaGVtYS1pbnN0YW5jZSI+CiAgPGRlc2NyaXB0aW9uPmRvbmF0aW9uPC9kZXNjcmlwdGlvbj4KPC9zZXJ2aWNlPg==</datiSpecificiServizioRequest>
            </pafn:paDemandPaymentNoticeRequest>
        </soapenv:Body>
    </soapenv:Envelope>`;
}
*/

function buildStringFromDate(rawDate) {
    var mm = rawDate.getMonth() + 1;
    var dd = rawDate.getDate();
    return [rawDate.getFullYear(), (mm>9 ? '' : '0') + mm, (dd>9 ? '' : '0') + dd].join('-');
}

function buildDebtPositionDynamicData(gpdSessionBundle) {    
    return {
        iupd: makeidMix(35),
        iuv1: `${gpdSessionBundle.debtPosition.iuvPrefix}${makeidNumber(15)}`,
        iuv2: makeidNumber(17),
        iuv3: makeidNumber(17),
        iban: gpdSessionBundle.debtPosition.iban,
        dueDate: addDays(30),
        retentionDate: addDays(90),
        transferId1: '1',
        transferId2: '2',
        amount: 300.00,
        receiptId: makeidMix(33),
        pspId: "60000000001",
        pspBrokerId: "60000000001",
        pspChannelId: "60000000001_01",
        pspName: "PSP Paolo",
        pspFiscalCode: "CF60000000006",
        idempotency: `60000000001_${makeidNumber(6)}${makeidMix(4)}`,
        applicationDate: buildStringFromDate(addDays(0)),
        transferDate: buildStringFromDate(addDays(1)),
    };
}

function buildCreateDebtPositionRequest(debtPosition, payer) {
    return {
        iupd: debtPosition.iupd,
        type: "F",
        fiscalCode: payer.fiscalCode,
        fullName: payer.name,
        streetName: payer.streetName,
        civicNumber: payer.civicNumber,
        postalCode: payer.postalCode,
        city: payer.city,
        province: payer.province,
        region: payer.region,
        country: payer.country,
        email: payer.email,
        phone: payer.phone,
        companyName: payer.companyName,
        officeName: payer.officeName,
        paymentOption: [
            {
                iuv: debtPosition.iuv1,
                amount: debtPosition.amount * 100,
                description: "Canone Unico Patrimoniale - SkyLab Inc.",
                isPartialPayment: false,
                dueDate: debtPosition.dueDate,
                retentionDate: debtPosition.retentionDate,
                fee: 0,
                transfer: [
                    {
                        idTransfer: debtPosition.transferId1,
                        amount: (debtPosition.amount * 100 / 3),
                        remittanceInformation: "Rata 1",
                        category: "9/0101108TS/",
                        iban: debtPosition.iban,
                    },
                    {
                        idTransfer: debtPosition.transferId2,
                        amount: (debtPosition.amount * 100 / 3) * 2,
                        remittanceInformation: "Rata 2",
                        category: "9/0101108TS/",
                        iban: debtPosition.iban,
                    }
                ]
            }
        ]
    };
}


function buildVerifyPaymentNoticeRequest(gpdSessionBundle, fiscalCode) {
   const brokerCode = gpdSessionBundle.brokerCode;
   const stationCode = gpdSessionBundle.stationCode;
   const noticeNumber = `3${gpdSessionBundle.debtPosition.iuv1}`
   return `<?xml version="1.0" encoding="utf-8"?>
        <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
            <Body>
                <paVerifyPaymentNoticeReq xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                    <idPA xmlns="">${fiscalCode}</idPA>
                    <idBrokerPA xmlns="">${brokerCode}</idBrokerPA>
                    <idStation xmlns="">${stationCode}</idStation>
                    <qrCode	xmlns="">
                        <fiscalCode>${fiscalCode}</fiscalCode>
                        <noticeNumber>${noticeNumber}</noticeNumber>
                    </qrCode>
                </paVerifyPaymentNoticeReq>
            </Body>
        </Envelope>`;
}

function buildActivatePaymentNoticeRequest(gpdSessionBundle, fiscalCode) {
    const pspId = gpdSessionBundle.debtPosition.pspId;
    const pspBrokerId = gpdSessionBundle.debtPosition.pspBrokerId;
    const pspChannelId = gpdSessionBundle.debtPosition.pspChannelId;
    //const fiscalCode = gpdSessionBundle.debtPosition.fiscalCode;
    const noticeNumber = `3${gpdSessionBundle.debtPosition.iuv1}`
    const amount = `${gpdSessionBundle.debtPosition.amount}.00`;
    const idempotency = gpdSessionBundle.debtPosition.idempotency;
    return `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:nod="http://pagopa-api.pagopa.gov.it/node/nodeForPsp.xsd">
        <soapenv:Header/>
        <soapenv:Body>
            <nod:activatePaymentNoticeReq>
                <idPSP>${pspId}</idPSP>
                <idBrokerPSP>${pspBrokerId}</idBrokerPSP>
                <idChannel>${pspChannelId}</idChannel>
                <password>pwdpwdpwd</password>
                <idempotencyKey>${idempotency}</idempotencyKey>
                <qrCode>
                    <fiscalCode>${fiscalCode}</fiscalCode>
                    <noticeNumber>${noticeNumber}</noticeNumber>
                </qrCode>
                <expirationTime>6000</expirationTime>
                <amount>${amount}</amount>
            </nod:activatePaymentNoticeReq>
        </soapenv:Body>
    </soapenv:Envelope>`;
}

function buildSendPaymentOutcomeRequest(gpdSessionBundle) {
    const pspId = gpdSessionBundle.debtPosition.pspId;
    const pspBrokerId = gpdSessionBundle.debtPosition.pspBrokerId;
    const pspChannelId = gpdSessionBundle.debtPosition.pspChannelId;
    const idempotency = gpdSessionBundle.debtPosition.idempotency;
    const paymentToken = gpdSessionBundle.debtPosition.paymentToken;
    const payerFiscalCode = gpdSessionBundle.payer.fiscalCode;
    const applicationDate = gpdSessionBundle.debtPosition.applicationDate;
    const transferDate = gpdSessionBundle.debtPosition.transferDate;
    const name = gpdSessionBundle.payer.name;
    const streetName = gpdSessionBundle.payer.streetName;
    const civicNumber = gpdSessionBundle.payer.civicNumber;
    const postalCode = gpdSessionBundle.payer.postalCode;
    const city = gpdSessionBundle.payer.city;
    const province = gpdSessionBundle.payer.province;
    const country = gpdSessionBundle.payer.country;
    const email = gpdSessionBundle.payer.email;
    return `<soapenv:Envelope>
        <soapenv:Body>
            <nod:sendPaymentOutcomeReq>
            <idPSP>${pspId}</idPSP>
            <idBrokerPSP>${pspBrokerId}</idBrokerPSP>
            <idChannel>${pspChannelId}</idChannel>
            <password>pwdpwdpwd</password>
            <idempotencyKey>${idempotency}</idempotencyKey>
            <paymentTokens>
                <paymentToken>${paymentToken}</paymentToken>
            </paymentTokens>
            <outcome>OK</outcome>
            <details>
                <paymentMethod>creditCard</paymentMethod>
                <paymentChannel>app</paymentChannel>
                <fee>1.50</fee>
                <primaryCiIncurredFee>0.50</primaryCiIncurredFee>
                <idBundle>1</idBundle>
                <idCiBundle>2</idCiBundle>
                <payer>
                    <uniqueIdentifier>
                        <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                        <entityUniqueIdentifierValue>${payerFiscalCode}</entityUniqueIdentifierValue>
                    </uniqueIdentifier>
                    <fullName>${name}</fullName>
                    <streetName>${streetName}</streetName>
                    <civicNumber>${civicNumber}</civicNumber>
                    <postalCode>${postalCode}</postalCode>
                    <city>${city}</city>
                    <stateProvinceRegion>${province}</stateProvinceRegion>
                    <country>${country}</country>
                    <e-mail>${email}</e-mail>
                </payer>
                <applicationDate>${applicationDate}</applicationDate>
                <transferDate>${transferDate}</transferDate>
            </details>
            </nod:sendPaymentOutcomeReq>
        </soapenv:Body>
    </soapenv:Envelope>`;
}

function buildSendRTRequest(gpdSessionBundle, fiscalCode) {
    const brokerCode = gpdSessionBundle.brokerCode;
    const stationCode = gpdSessionBundle.stationCode;
    const pspId = gpdSessionBundle.debtPosition.pspId;
    const pspFiscalCode = gpdSessionBundle.debtPosition.pspFiscalCode;
    const pspName = gpdSessionBundle.debtPosition.pspName;
    const pspChannelId = gpdSessionBundle.debtPosition.pspChannelId;
    const iuv = gpdSessionBundle.debtPosition.iuv1;
    const noticeNumber = `3${gpdSessionBundle.debtPosition.iuv1}`;
    const iban = gpdSessionBundle.debtPosition.iban;
    const receiptId = gpdSessionBundle.debtPosition.paymentToken; //gpdSessionBundle.debtPosition.receiptId;
    const amount = `${gpdSessionBundle.debtPosition.amount}.00`;
    const transferAmountRate = gpdSessionBundle.debtPosition.amount / 3;
    const payerFiscalCode = gpdSessionBundle.payer.fiscalCode;
    const applicationDate = gpdSessionBundle.debtPosition.applicationDate;
    const transferDate = gpdSessionBundle.debtPosition.transferDate;
    const name = gpdSessionBundle.payer.name;
    const streetName = gpdSessionBundle.payer.streetName;
    const civicNumber = gpdSessionBundle.payer.civicNumber;
    const postalCode = gpdSessionBundle.payer.postalCode;
    const city = gpdSessionBundle.payer.city;
    const province = gpdSessionBundle.payer.province;
    const country = gpdSessionBundle.payer.country;
    const email = gpdSessionBundle.payer.email;
    const companyName = gpdSessionBundle.payer.companyName;
    const officeName = gpdSessionBundle.payer.officeName;
    return `<?xml version="1.0" encoding="utf-8"?>
        <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
            <Body>
                <paSendRTReq xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                    <idPA xmlns="">${fiscalCode}</idPA>
                    <idBrokerPA xmlns="">${brokerCode}</idBrokerPA>
                    <idStation xmlns="">${stationCode}</idStation>
                    <receipt xmlns="">
                        <receiptId>${receiptId}</receiptId>
                        <noticeNumber>${noticeNumber}</noticeNumber>
                        <fiscalCode>${fiscalCode}</fiscalCode>
                        <outcome>OK</outcome>
                        <creditorReferenceId>${iuv}</creditorReferenceId>
                        <paymentAmount>${amount}</paymentAmount>
                        <description>Pagamento compenso spettacolo "Tel chi el telun"</description>
                        <companyName>${companyName}</companyName>
                        <officeName>${officeName}</officeName>
                        <debtor>
                            <uniqueIdentifier>
                                <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                <entityUniqueIdentifierValue>${payerFiscalCode}</entityUniqueIdentifierValue>
                            </uniqueIdentifier>
                            <fullName>${name}</fullName>
                            <streetName>${streetName}</streetName>
                            <civicNumber>${civicNumber}</civicNumber>
                            <postalCode>${postalCode}</postalCode>
                            <city>${city}</city>
                            <stateProvinceRegion>${province}</stateProvinceRegion>
                            <country>${country}</country>
                            <e-mail>${email}</e-mail>
                        </debtor>
                        <transferList>
                            <transfer>
                                <idTransfer>1</idTransfer>
                                <transferAmount>${transferAmountRate}.00</transferAmount>
                                <fiscalCodePA>${fiscalCode}</fiscalCodePA>
                                <IBAN>${iban}</IBAN>
                                <remittanceInformation>Rata 1</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                            <transfer>
                                <idTransfer>2</idTransfer>
                                <transferAmount>${transferAmountRate * 2}.00</transferAmount>
                                <fiscalCodePA>${fiscalCode}</fiscalCodePA>
                                <IBAN>${iban}</IBAN>
                                <remittanceInformation>Rata 2</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                        </transferList>
                        <idPSP>${pspId}</idPSP>
                        <pspFiscalCode>${pspFiscalCode}</pspFiscalCode>
                        <PSPCompanyName>${pspName}</PSPCompanyName>
                        <idChannel>${pspChannelId}</idChannel>
                        <channelDescription>app</channelDescription>
                        <payer>
                            <uniqueIdentifier>
                                <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                <entityUniqueIdentifierValue>${payerFiscalCode}</entityUniqueIdentifierValue>
                            </uniqueIdentifier>
                            <fullName>${name}</fullName>
                            <streetName>${streetName}</streetName>
                            <civicNumber>${civicNumber}</civicNumber>
                            <postalCode>${postalCode}</postalCode>
                            <city>${city}</city>
                            <stateProvinceRegion>${province}</stateProvinceRegion>
                            <country>${country}</country>
                            <e-mail>${email}</e-mail>
                        </payer>
                        <paymentMethod>creditCard</paymentMethod>
                        <fee>1.50</fee>
                        <paymentDateTime>${applicationDate}T12:00:00</paymentDateTime>
                        <applicationDate>${applicationDate}</applicationDate>
                        <transferDate>${transferDate}</transferDate>
                    </receipt>
                </paSendRTReq>
            </Body>
        </Envelope>`;
}

module.exports = {
    buildActivatePaymentNoticeRequest,
    buildCreateDebtPositionRequest,
    buildDebtPositionDynamicData,
    buildSendPaymentOutcomeRequest,    
    buildSendRTRequest,
    buildVerifyPaymentNoticeRequest,
}