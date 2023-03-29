export function getpaVerifyPaymentNoticeReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv_1) {
  let payload = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:nod="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
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
  return payload;
}

export function getpaGetPaymentReqBody(creditorInstitutionCode, idBrokerPA, idStation, iuv_1) {
  let payload = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
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

  return payload;
}

export function getpaSendRTReqBody(creditorInstitutionCode, idBrokerPA, idStation, receiptId, iuv_1) {
  let payload = `<soapenv:Envelope xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
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

  return payload;
}

export function getDebtPosition(iupd, iuv_1, iuv_2, iuv_3, due_date, retention_date, transfer_id_1, transfer_id_2) {
  return JSON.stringify({
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
}
