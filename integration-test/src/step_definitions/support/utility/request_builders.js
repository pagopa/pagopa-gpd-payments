const { addDays, buildStringFromDate, makeidNumber, makeidMix,  } = require("./helpers");


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


function buildDemandPaymentNoticeRequest(gpsSessionBundle) {
    const organizationCode = gpsSessionBundle.organizationCode;
    const brokerCode = gpsSessionBundle.brokerCode;
    const stationCode = gpsSessionBundle.stationCode;
    const serviceCode = gpsSessionBundle.serviceCode;
    const base64ServiceData = gpsSessionBundle.serviceData;
    return `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
        <soapenv:Body>
            <pafn:paDemandPaymentNoticeRequest>
                <idPA>${organizationCode}</idPA>
                <idBrokerPA>${brokerCode}</idBrokerPA>
                <idStation>${stationCode}</idStation>
                <idServizio>${serviceCode}</idServizio>
                <datiSpecificiServizioRequest>${base64ServiceData}</datiSpecificiServizioRequest>
            </pafn:paDemandPaymentNoticeRequest>
        </soapenv:Body>
    </soapenv:Envelope>`;
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
        pspId: process.env.pspId,
        pspBrokerId: process.env.pspBrokerId,
        pspChannelId: process.env.pspChannelId,
        pspName: process.env.pspName,
        pspFiscalCode: process.env.pspFiscalCode,
        idempotency: `${process.env.pspBrokerId}_${makeidNumber(6)}${makeidMix(4)}`,
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
        switchToExpired: false,
        paymentOption: [
            {
                iuv: debtPosition.iuv1,
                amount: debtPosition.amount * 100,
                description: "Canone Unico Patrimoniale - SkyLab Inc.",
                isPartialPayment: false,
                dueDate: debtPosition.dueDate,
                retentionDate: debtPosition.retentionDate,
                fee: 0,
                paymentOptionMetadata: [
                    {
                      "key": "po-metadata-key",
                      "value": "po-metadata-value"
                    }
                ],
                transfer: [
                    {
                        idTransfer: debtPosition.transferId1,
                        amount: (debtPosition.amount * 100 / 3),
                        remittanceInformation: "Rata 1",
                        category: "9/0101108TS/",
                        iban: debtPosition.iban,
                        transferMetadata: [
                            {
                              "key": "key",
                              "value": "string"
                            }
                        ]
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
    const pspPassword = process.env.pspPassword;

    return `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:nod="http://pagopa-api.pagopa.gov.it/node/nodeForPsp.xsd">
        <soapenv:Header/>
        <soapenv:Body>
            <nod:activatePaymentNoticeReq>
                <idPSP>${pspId}</idPSP>
                <idBrokerPSP>${pspBrokerId}</idBrokerPSP>
                <idChannel>${pspChannelId}</idChannel>
                <password>${pspPassword}</password>
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
    const pspPassword = process.env.pspPassword;
    return `<soapenv:Envelope>
        <soapenv:Body>
            <nod:sendPaymentOutcomeReq>
            <idPSP>${pspId}</idPSP>
            <idBrokerPSP>${pspBrokerId}</idBrokerPSP>
            <idChannel>${pspChannelId}</idChannel>
            <password>${pspPassword}</password>
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
    const receiptId = gpdSessionBundle.debtPosition.receiptId;
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

function buildGetPaymentReq(gpdSessionBundle, fiscalCode) {
	const brokerCode = gpdSessionBundle.brokerCode;
	const stationCode = gpdSessionBundle.stationCode;
	const noticeNumber = `${gpdSessionBundle.debtPosition.iuv1}`;
	const amount = `${gpdSessionBundle.debtPosition.amount}.00`;
	return `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                        <soapenv:Header />
                        <soapenv:Body>
                            <pafn:paGetPaymentReq>
                                <idPA>${fiscalCode}</idPA>
                                <idBrokerPA>${brokerCode}</idBrokerPA>
                                <idStation>${stationCode}</idStation>
                                <qrCode>
                                    <fiscalCode>${fiscalCode}</fiscalCode>
                                    <noticeNumber>3${noticeNumber}</noticeNumber>
                                </qrCode>
                                <amount>${amount}</amount>
                            </pafn:paGetPaymentReq>
                        </soapenv:Body>
                    </soapenv:Envelope>`;
	}
	
function buildGetPaymentV2Req(gpdSessionBundle, fiscalCode) {
	const brokerCode = gpdSessionBundle.brokerCode;
	const stationCode = gpdSessionBundle.stationCode;
	const noticeNumber = `${gpdSessionBundle.debtPosition.iuv1}`;
	const amount = `${gpdSessionBundle.debtPosition.amount}.00`;
	return `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
                        <soapenv:Header />
                        <soapenv:Body>
                            <pafn:paGetPaymentV2Request>
                                <idPA>${fiscalCode}</idPA>
                                <idBrokerPA>${brokerCode}</idBrokerPA>
                                <idStation>${stationCode}</idStation>
                                <qrCode>
                                    <fiscalCode>${fiscalCode}</fiscalCode>
                                    <noticeNumber>3${noticeNumber}</noticeNumber>
                                </qrCode>
                                <amount>${amount}</amount>
                            </pafn:paGetPaymentV2Request>
                        </soapenv:Body>
                    </soapenv:Envelope>`;
	}
	
	function buildApiConfigServiceCreationCIRequest(creditorInstitutionCode){
		return {
			  "address": {
			    "city": "Lorem",
			    "country_code": "RM",
			    "location": "Via delle vie 3",
			    "tax_domicile": "string",
			    "zip_code": "00187"
			  },
			  "business_name": "Comune di Lorem Ipsum",
			  "cbill_code": "1234567890100",
			  "creditor_institution_code": creditorInstitutionCode,
			  "enabled": true,
			  "psp_payment": true,
			  "reporting_ftp": false,
			  "reporting_zip": false
			};
	}
	
	function buildApiConfigServiceCreationIbansRequest(dueDate, validityDate){
		return {
			  "description": "Riscossione Tributi",
			  "due_date": dueDate,
			  "iban": "IT99C0222211111000000000000",
			  "is_active": true,
			  "labels": [],
			  "validity_date": validityDate
		};
	}
	
	function buildApiConfigServiceCreationBrokerRequest(brokerCode){
		return {
			  "broker_code": brokerCode,
			  "description": "Lorem ipsum dolor sit amet",
			  "enabled": true,
			  "extended_fault_bean": true
		};
	}
	
	function buildApiConfigServiceCreationStationRequest(stationCode, brokerCode, ip){
		return {
			  "broker_code": brokerCode,
			  "broker_description": "Lorem ipsum dolor sit amet",
			  "enabled": true,
			  "flag_online": true,
			  "invio_rt_istantaneo": false,
			  "ip": ip,
			  "ip_4mod": "",
			  "password": "pagopa_test",
			  "pof_service": "gpd-payments/api/v1",
			  "port": 65535,
			  "port_4mod": 65535,
			  "primitive_version": 1,
			  "protocol": "HTTPS",
			  "protocol_4mod": "HTTP",
			  "proxy_enabled": true,
			  "proxy_host": "10.79.20.33",
			  "proxy_password": "",
			  "proxy_port": 80,
			  "proxy_username": "",
			  "redirect_ip": "",
			  "redirect_path": "",
			  "redirect_port": 80,
			  "redirect_protocol": "HTTP",
			  "redirect_query_string": "",
			  "service": "gpd-payments/api/v1",
			  "service_4mod": "",
			  "station_code": stationCode,
			  "target_host": "",
			  "target_host_pof": "",
			  "target_path": "",
			  "target_path_pof": "",
			  "target_port": 0,
			  "target_port_pof": 0,
			  "thread_number": 1,
			  "timeout_a": 15,
			  "timeout_b": 30,
			  "timeout_c": 120,
			  "version": 2
		};
	}
	
	function buildApiConfigServiceCreationECStationAssociation(stationCode){
		return {
			  "application_code": 3,
			  "aux_digit": 3,
			  "broadcast": true,
			  "mod4": true,
			  "segregation_code": 47,
			  "station_code": stationCode
			};
	}


module.exports = {
    buildActivatePaymentNoticeRequest,
    buildCreateDebtPositionRequest,
    buildDebtPositionDynamicData,
    buildDemandPaymentNoticeRequest,
    buildGPSOrganizationCreationRequest,
    buildGPSServiceCreationRequest,
    buildSendPaymentOutcomeRequest,    
    buildSendRTRequest,
    buildVerifyPaymentNoticeRequest,
    buildGetPaymentReq,
    buildGetPaymentV2Req,
    buildApiConfigServiceCreationCIRequest,
    buildApiConfigServiceCreationIbansRequest,
    buildApiConfigServiceCreationBrokerRequest,
    buildApiConfigServiceCreationStationRequest,
    buildApiConfigServiceCreationECStationAssociation
}