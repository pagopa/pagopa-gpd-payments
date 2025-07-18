package it.gov.pagopa.payments.service.primitive;

import feign.FeignException;
import it.gov.pagopa.payments.client.GpsClient;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import it.gov.pagopa.payments.model.Type;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.model.spontaneous.*;
import it.gov.pagopa.payments.utils.CommonUtil;
import it.gov.pagopa.payments.utils.Validator;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.SAXException;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class DemandPayService {
    public static final String TEXT_XML_NODE = "#text";

    @Value(value = "${xsd.generic-service}")
    private Resource xsdGenericService;

    @Autowired
    private ObjectFactory factory;

    @Autowired
    private GpsClient gpsClient;

    @Transactional
    public PaDemandPaymentNoticeResponse paDemandPaymentNotice(PaDemandPaymentNoticeRequest request)
            throws DatatypeConfigurationException, ParserConfigurationException, IOException, SAXException, XMLStreamException {

        List<ServicePropertyModel> attributes = this.mapDatiSpecificiServizio(xsdGenericService, request);

        SpontaneousPaymentModel spontaneousPayment = SpontaneousPaymentModel.builder()
                .service(ServiceModel.builder().id(request.getIdServizio()).properties(attributes).build())
                .debtor(DebtorModel.builder() // TODO: take the info from the request
                .type(Type.F).fiscalCode("ANONIMO").fullName("ANONIMO").build()).build();

        PaymentPositionModel gpsResponse;
        try {
            log.debug("[paDemandPaymentNotice] call GPS");
            gpsResponse = gpsClient.createSpontaneousPayments(request.getIdPA(), spontaneousPayment);
        } catch (FeignException.NotFound e) {
            log.error("[paDemandPaymentNotice] GPS Error not found", e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
        } catch (Exception e) {
            log.error("[paDemandPaymentNotice] GPS Generic Error", e);
            throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
        }
        return createPaDemandPaymentNoticeResponse(gpsResponse);
    }

    private PaDemandPaymentNoticeResponse createPaDemandPaymentNoticeResponse(PaymentPositionModel gpsResponse)
            throws DatatypeConfigurationException {
        var result = factory.createPaDemandPaymentNoticeResponse();
        result.setOutcome(StOutcome.OK);
        result.setFiscalCodePA(gpsResponse.getPaymentOption().get(0).getOrganizationFiscalCode());

        CtQrCode ctQrCode = factory.createCtQrCode();
        ctQrCode.setFiscalCode(gpsResponse.getPaymentOption().get(0).getOrganizationFiscalCode());
        ctQrCode.setNoticeNumber(gpsResponse.getPaymentOption().get(0).getNav());
        result.setQrCode(ctQrCode);

        result.setCompanyName(Validator.validateCompanyName(gpsResponse.getCompanyName()));
        result.setOfficeName(Validator.validateOfficeName(gpsResponse.getOfficeName()));
        result.setPaymentDescription(Validator.validatePaymentOptionDescription(gpsResponse.getPaymentOption().get(0).getDescription()));
        CtPaymentOptionsDescriptionListPA ctPaymentOptionsDescriptionListPA = factory.createCtPaymentOptionsDescriptionListPA();

        CtPaymentOptionDescriptionPA ctPaymentOptionDescriptionPA = factory.createCtPaymentOptionDescriptionPA();

        var ccp = gpsResponse.getPaymentOption().get(0).getTransfer().stream()
                .noneMatch(elem -> elem.getPostalIban() == null || elem.getPostalIban().isBlank());
        ctPaymentOptionDescriptionPA.setAllCCP(ccp);

        ctPaymentOptionDescriptionPA.setAmount(BigDecimal.valueOf(gpsResponse.getPaymentOption().get(0).getAmount()));

        var date = gpsResponse.getPaymentOption().get(0).getDueDate();
        ctPaymentOptionDescriptionPA.setDueDate(DatatypeFactory.newInstance().newXMLGregorianCalendar(String.valueOf(date)));

        ctPaymentOptionDescriptionPA.setOptions(StAmountOption.EQ);
        ctPaymentOptionDescriptionPA.setDetailDescription(Validator
                .validatePaymentOptionDescription(gpsResponse.getPaymentOption().get(0).getDescription()));
        ctPaymentOptionsDescriptionListPA.setPaymentOptionDescription(ctPaymentOptionDescriptionPA);
        result.setPaymentList(ctPaymentOptionsDescriptionListPA);
        return result;
    }

    private List<ServicePropertyModel> mapDatiSpecificiServizio(Resource xsdGenericService, PaDemandPaymentNoticeRequest request)
            throws ParserConfigurationException, SAXException, IOException, XMLStreamException {
        CommonUtil.syntacticValidationXml(request.getDatiSpecificiServizioRequest(), xsdGenericService.getFile());

        // parse XML into Document
        DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
        xmlFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = xmlFactory.newDocumentBuilder();
        var document = builder.parse(new ByteArrayInputStream(request.getDatiSpecificiServizioRequest()));

        // map XML tags into list of ServicePropertyModel
        var nodes = document.getElementsByTagName("service").item(0).getChildNodes();
        List<ServicePropertyModel> attributes = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (!TEXT_XML_NODE.equals(node.getNodeName())) {
                var name = node.getNodeName();
                var value = node.getTextContent();
                attributes.add(ServicePropertyModel.builder().name(name).value(value).build());
            }
        }
        return attributes;
    }
}
