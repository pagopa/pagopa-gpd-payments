const { makeidNumber, makeidMix, addDays } = require("./helpers");

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


function buildCreateDebtPositionRequest(debtPosition) {
    return {
        iupd: debtPosition.iupd,
        type: "F",
        fiscalCode: "VNTMHL76M09H501D",
        fullName: "Michele Ventimiglia",
        streetName: "via Washington",
        civicNumber: "11",
        postalCode: "89812",
        city: "Pizzo Calabro",
        province: "VV",
        region: "CA",
        country: "IT",
        email: "micheleventimiglia@skilabmail.com",
        phone: "333-123456789",
        companyName: "SkyLab Inc.",
        officeName: "SkyLab - Sede via Washington",
        paymentOption: [
            {
                iuv: debtPosition.iuv1,
                amount: 35000,
                description: "Canone Unico Patrimoniale - SkyLab Inc.",
                isPartialPayment: false,
                dueDate: debtPosition.dueDate,
                retentionDate: debtPosition.retentionDate,
                fee: 0,
                transfer: [
                    {
                        idTransfer: debtPosition.transferId1,
                        amount: 10000,
                        remittanceInformation: "Rata 1",
                        category: "9/0101108TS/",
                        iban: "IT0000000000000000000000000"
                    },
                    {
                        idTransfer: debtPosition.transferId2,
                        amount: 25000,
                        remittanceInformation: "Rata 2",
                        category: "9/0101108TS/",
                        iban: "IT0000000000000000000000000"
                    }
                ]
            },
            {
                iuv: debtPosition.iuv2,
                amount: 5500,
                description: "Canone Unico Patrimoniale - SkyLab Inc. - Not Final",
                isPartialPayment: true,
                dueDate: debtPosition.dueDate,
                retentionDate: debtPosition.retentionDate,
                fee: 0,
                transfer: [
                    {
                        idTransfer: debtPosition.transferId1,
                        amount: 4000,
                        remittanceInformation: "Rata 1",
                        category: "9/0101108TS/",
                        iban: "IT0000000000000000000000000"
                    },
                    {
                        idTransfer: debtPosition.transferId2,
                        amount: 1500,
                        remittanceInformation: "Rata 2",
                        category: "9/0101108TS/",
                        iban: "IT0000000000000000000000000"
                    }
                ]
            },
            {
                iuv: debtPosition.iuv3,
                amount: 14000,
                description: "Canone Unico Patrimoniale - SkyLab Inc. - Not Final 2",
                isPartialPayment: true,
                dueDate: debtPosition.dueDate,
                retentionDate: debtPosition.retentionDate,
                fee: 0,
                transfer: [
                    {
                        idTransfer: debtPosition.transferId1,
                        amount: 4000,
                        remittanceInformation: "Rata 1",
                        category: "9/0101108TS/",
                        iban: "IT0000000000000000000000000"
                    },
                    {
                        idTransfer: debtPosition.transferId2,
                        amount: 10000,
                        remittanceInformation: "Rata 2",
                        category: "9/0101108TS/",
                        iban: "IT0000000000000000000000000"
                    }
                ]
            }
        ]
    };
}


function buildVerifyPaymentNoticeRequest(gpdSessionBundle) {
   const organizationCode = gpdSessionBundle.organizationCode;
   const brokerCode = gpdSessionBundle.brokerCode;
   const stationCode = gpdSessionBundle.stationCode;
   const noticeNumber = `3${gpdSessionBundle.debtPosition.iuv1}`
   return `<?xml version="1.0" encoding="utf-8"?>
        <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
            <Body>
                <paVerifyPaymentNoticeReq xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                    <idPA xmlns="">${organizationCode}</idPA>
                    <idBrokerPA xmlns="">${brokerCode}</idBrokerPA>
                    <idStation xmlns="">${stationCode}</idStation>
                    <qrCode	xmlns="">
                        <fiscalCode>${organizationCode}</fiscalCode>
                        <noticeNumber>${noticeNumber}</noticeNumber>
                    </qrCode>
                </paVerifyPaymentNoticeReq>
            </Body>
        </Envelope>`;
}

function buildGetPaymentRequest(gpdSessionBundle) {
    const organizationCode = gpdSessionBundle.organizationCode;
    const brokerCode = gpdSessionBundle.brokerCode;
    const stationCode = gpdSessionBundle.stationCode;
    const noticeNumber = `3${gpdSessionBundle.debtPosition.iuv1}`
    return `<?xml version="1.0" encoding="utf-8"?>
        <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
            <Body>
                <paGetPaymentReq xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                    <idPA xmlns="">${organizationCode}</idPA>
                    <idBrokerPA xmlns="">${brokerCode}</idBrokerPA>
                    <idStation xmlns="">${stationCode}</idStation>
                    <qrCode xmlns="">
                        <fiscalCode>${organizationCode}</fiscalCode>
                        <noticeNumber>${noticeNumber}</noticeNumber>
                    </qrCode>
                    <amount xmlns="">350.00</amount>
                </paGetPaymentReq>
            </Body>
        </Envelope>`;
}

function buildSendRTRequest(gpdSessionBundle) {
    const organizationCode = gpdSessionBundle.organizationCode;
    const brokerCode = gpdSessionBundle.brokerCode;
    const stationCode = gpdSessionBundle.stationCode;
    const noticeNumber = `3${gpdSessionBundle.debtPosition.iuv1}`
    const receiptId = gpdSessionBundle.debtPosition.receiptId;
    const dueDateRaw = gpdSessionBundle.debtPosition.dueDate;
    const iuv = gpdSessionBundle.debtPosition.iuv1;
    var mm = dueDateRaw.getMonth() + 1;
    var dd = dueDateRaw.getDate();
    const dueDate = [dueDateRaw.getFullYear(), (mm>9 ? '' : '0') + mm, (dd>9 ? '' : '0') + dd].join('-');

    return `<?xml version="1.0" encoding="utf-8"?>
        <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
            <Body>
                <paSendRTReq xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                    <idPA xmlns="">${organizationCode}</idPA>
                    <idBrokerPA xmlns="">${brokerCode}</idBrokerPA>
                    <idStation xmlns="">${stationCode}</idStation>
                    <receipt xmlns="">
                        <receiptId>${receiptId}</receiptId>
                        <noticeNumber>${noticeNumber}</noticeNumber>
                        <fiscalCode>${organizationCode}</fiscalCode>
                        <outcome>OK</outcome>
                        <creditorReferenceId>${iuv}</creditorReferenceId>
                        <paymentAmount>350.00</paymentAmount>
                        <description>Pagamento compenso spettacolo "Tel chi el telun"</description>
                        <companyName>SkyLab Inc.</companyName>
                        <officeName>SkyLab - Sede via Washington</officeName>
                        <debtor>
                            <uniqueIdentifier>
                                <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                <entityUniqueIdentifierValue>375647785689566</entityUniqueIdentifierValue>
                            </uniqueIdentifier>
                            <fullName>Michele Ventimiglia</fullName>
                            <streetName>via Washington</streetName>
                            <civicNumber>11</civicNumber>
                            <postalCode>89812</postalCode>
                            <city>Pizzo Calabro</city>
                            <stateProvinceRegion>Vibo Valentia, Calabria</stateProvinceRegion>
                            <country>IT</country>
                            <e-mail>micheleventimiglia@skilabmail.com</e-mail>
                        </debtor>
                        <transferList>
                            <transfer>
                                <idTransfer>1</idTransfer>
                                <transferAmount>100.00</transferAmount>
                                <fiscalCodePA>${organizationCode}</fiscalCodePA>
                                <IBAN>IT0000000000000000000000000</IBAN>
                                <remittanceInformation>Rata 1</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                            <transfer>
                                <idTransfer>2</idTransfer>
                                <transferAmount>250.00</transferAmount>
                                <fiscalCodePA>${organizationCode}</fiscalCodePA>
                                <IBAN>IT0000000000000000000000001</IBAN>
                                <remittanceInformation>Rata 2</remittanceInformation>
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
                            <fullName>Michele Ventimiglia</fullName>
                            <streetName>via Washington</streetName>
                            <civicNumber>11</civicNumber>
                            <postalCode>89812</postalCode>
                            <city>Pizzo Calabro</city>
                            <stateProvinceRegion>Vibo Valentia, Calabria</stateProvinceRegion>
                            <country>IT</country>
                            <e-mail>micheleventimiglia@skilabmail.com</e-mail>
                        </payer>
                        <paymentMethod>creditCard</paymentMethod>
                        <fee>2.00</fee>
                        <paymentDateTime>${dueDate}T08:03:17</paymentDateTime>
                        <applicationDate>${dueDate}</applicationDate>
                        <transferDate>${dueDate}</transferDate>
                        <metadata>
                            <mapEntry>
                                <key>keytest</key>
                                <value>1</value>
                            </mapEntry>
                        </metadata>
                    </receipt>
                </paSendRTReq>
            </Body>
        </Envelope>`;
}

function buildCreateStationRequest(brokerId, stationId) {
    return {
        broker_code: brokerId, 
        enabled: "true", 
        ip: "192.168.1.102", 
        password: "password", 
        port: 443, 
        protocol: "HTTPS", 
        service: "test", 
        station_code: stationId, 
        thread_number: 1, 
        timeout_a: 15, 
        timeout_b: 30, 
        timeout_c: 120, 
        version: 1, 
        flag_online: false, 
        invio_rt_istantaneo: false, 
        ip_4mod: false, 
        new_password: "newpassword", 
        port_4mod: "1000", 
        protocol_4mod: "HTTPS", 
        proxy_enabled: false, 
        proxy_host: "localhost", 
        proxy_password: "root", 
        proxy_port: "2501", 
        proxy_username: "root", 
        redirect_ip: "192.168.201.166", 
        redirect_path: "redirected", 
        redirect_port: "1001", 
        redirect_protocol: "HTTPS", 
        redirect_query_string: "", 
        service_4mod: "testServ", 
        target_host: "192.168.100.100", 
        target_port: 443, 
        target_path: "testServ", 
        primitive_version: 1, 
        pof_service: "testPOF"
    };
}

function buildCreateECStationRelationRequest(stationId) {
    return {
        station_code: stationId,
        enabled: true,
        version: 1,
        aux_digit: 0,
        application_code: 99,
        segregation_code: 99,
        mod4: false,
        broadcast: false
    }
}

function buildDebtPositionDynamicData() {
    return {
        iupd: makeidMix(35),
        iuv1: makeidNumber(17),
        iuv2: makeidNumber(17),
        iuv3: makeidNumber(17),
        dueDate: addDays(30),
        retentionDate: addDays(90),
        transferId1: '1',
        transferId2: '2',
        receiptId: makeidMix(33),
    };
}

module.exports = {
    buildGPSServiceCreationRequest,
    buildGPSOrganizationCreationRequest,
    buildValidDemandPaymentNoticeRequest,
    buildInvalidDemandPaymentNoticeRequest,
    buildVerifyPaymentNoticeRequest,
    buildGetPaymentRequest,
    buildSendRTRequest,
    buildCreateECStationRelationRequest,
    buildCreateStationRequest,
    buildCreateDebtPositionRequest,
    buildDebtPositionDynamicData
}