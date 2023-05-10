package it.gov.pagopa.payments.service;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import com.microsoft.azure.storage.StorageException;
import feign.FeignException;
import feign.RetryableException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntity;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.model.partner.*;
import it.gov.pagopa.payments.model.spontaneous.*;
import it.gov.pagopa.payments.utils.CommonUtil;
import it.gov.pagopa.payments.utils.CustomizedMapper;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class PartnerService {

  private static final String DEBT_POSITION_STATUS_ERROR =
      "[Check DP] Debt position status error: ";
  public static final String TEXT_XML_NODE = "#text";

  public static final String DEBTOR_PROPERTY = "debtor";

  public static final String DOCUMENT_PROPERTY = "document";

  public static final String STATUS_PROPERTY = "status";

  public static final String PAYMENT_DATE_PROPERTY = "paymentDate";

  @Value(value = "${xsd.generic-service}")
  private Resource xsdGenericService;

  @Autowired private ObjectFactory factory;

  @Autowired private GpdClient gpdClient;

  @Autowired private GpsClient gpsClient;

  @Autowired private TableClient tableClient;

  @Autowired private PaymentValidator paymentValidator;

  @Autowired private CustomizedMapper customizedModelMapper;

  private static final String DBERROR = "Error in organization table connection";

  @Transactional(readOnly = true)
  public PaVerifyPaymentNoticeRes paVerifyPaymentNotice(PaVerifyPaymentNoticeReq request)
      throws DatatypeConfigurationException, PartnerValidationException {

    log.debug(
        "[paVerifyPaymentNotice] isAuthorize check [noticeNumber={}]",
        request.getQrCode().getNoticeNumber());
    paymentValidator.isAuthorize(
        request.getIdPA(), request.getIdBrokerPA(), request.getIdStation());

    log.debug(
        "[paVerifyPaymentNotice] get payment option [noticeNumber={}]",
        request.getQrCode().getNoticeNumber());
    PaymentsModelResponse paymentOption = null;

    try {
      // with Aux-Digit = 3
      // notice number format is define as follows:
      // 3<segregation code(2n)><IUV base(13n)><IUV check digit(2n)>
      // GPD service works on IUVs directly, so we remove the Aux-Digit
      paymentOption =
          gpdClient.getPaymentOption(
              request.getIdPA(), request.getQrCode().getNoticeNumber().substring(1));
    } catch (FeignException.NotFound e) {
      log.error(
          "[paVerifyPaymentNotice] GPD Error not found [noticeNumber={}]",
          request.getQrCode().getNoticeNumber(),
          e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
    } catch (Exception e) {
      log.error(
          "[paVerifyPaymentNotice] GPD Generic Error [noticeNumber={}]",
          request.getQrCode().getNoticeNumber(),
          e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
    }

    checkDebtPositionStatus(paymentOption);

    log.info(
        "[paVerifyPaymentNotice] Response OK generation [noticeNumber={}]",
        request.getQrCode().getNoticeNumber());

    return this.generatePaVerifyPaymentNoticeResponse(paymentOption);
  }

  @Transactional(readOnly = true)
  public PaGetPaymentRes paGetPayment(PaGetPaymentReq request)
      throws DatatypeConfigurationException, PartnerValidationException {
    log.debug(
        "[paGetPayment] method call [noticeNumber={}]", request.getQrCode().getNoticeNumber());
    PaymentsModelResponse paymentOption =
        this.manageGetPaymentRequest(
            request.getIdPA(),
            request.getIdBrokerPA(),
            request.getIdStation(),
            request.getQrCode());
    log.info(
        "[paGetPayment] Response OK generation [noticeNumber={}]",
        request.getQrCode().getNoticeNumber());
    return this.generatePaGetPaymentResponse(paymentOption, request);
  }

  @Transactional(readOnly = true)
  public PaGetPaymentV2Response paGetPaymentV2(PaGetPaymentV2Request request)
      throws DatatypeConfigurationException, PartnerValidationException {
    log.debug(
        "[paGetPaymentV2] method call [noticeNumber={}]", request.getQrCode().getNoticeNumber());
    PaymentsModelResponse paymentOption =
        this.manageGetPaymentRequest(
            request.getIdPA(),
            request.getIdBrokerPA(),
            request.getIdStation(),
            request.getQrCode());
    log.info(
        "[paGetPaymentV2] Response OK generation [noticeNumber={}]",
        request.getQrCode().getNoticeNumber());
    return this.generatePaGetPaymentResponse(paymentOption, request);
  }

  @Transactional
  public PaSendRTRes paSendRT(PaSendRTReq request) {

    PaymentOptionModelResponse paymentOption = managePaSendRtRequest(request);

    if (!PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus())) {
      log.error(
          "[paSendRT] Payment Option [statusError: {}] [noticeNumber={}]",
          paymentOption.getStatus(),
          request.getReceipt().getNoticeNumber());
      throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    }

    log.info(
        "[paSendRT] Generate Response [noticeNumber={}]", request.getReceipt().getNoticeNumber());
    // status is always equals to PO_PAID
    return generatePaSendRTResponse();
  }

  @Transactional
  public PaSendRTV2Response paSendRTV2(PaSendRTV2Request request) {

    PaymentOptionModelResponse paymentOption = managePaSendRtRequest(request);

    if (!PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus())) {
      log.error(
          "[paSendRTV2] Payment Option [statusError: {}] [noticeNumber={}]",
          paymentOption.getStatus(),
          request.getReceipt().getNoticeNumber());
      throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    }

    log.info(
        "[paSendRTV2] Generate Response [noticeNumber={}]", request.getReceipt().getNoticeNumber());
    // status is always equals to PO_PAID
    return generatePaSendRTV2Response();
  }

  @Transactional
  public PaDemandPaymentNoticeResponse paDemandPaymentNotice(PaDemandPaymentNoticeRequest request)
      throws DatatypeConfigurationException,
          ParserConfigurationException,
          IOException,
          SAXException,
          XMLStreamException {
    log.debug("[paDemandPaymentNotice] isAuthorize check");
    paymentValidator.isAuthorize(
        request.getIdPA(), request.getIdBrokerPA(), request.getIdStation());

    List<ServicePropertyModel> attributes = mapDatiSpecificiServizio(request);

    SpontaneousPaymentModel spontaneousPayment =
        SpontaneousPaymentModel.builder()
            .service(
                ServiceModel.builder().id(request.getIdServizio()).properties(attributes).build())
            .debtor(
                DebtorModel.builder() // TODO: take the info from the request
                    .type(Type.F)
                    .fiscalCode("ANONIMO")
                    .fullName("ANONIMO")
                    .build())
            .build();

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

  private List<ServicePropertyModel> mapDatiSpecificiServizio(PaDemandPaymentNoticeRequest request)
      throws ParserConfigurationException, SAXException, IOException, XMLStreamException {
    CommonUtil.syntacticValidationXml(
        request.getDatiSpecificiServizioRequest(), xsdGenericService.getFile());

    // parse XML into Document
    DocumentBuilderFactory xmlFactory = DocumentBuilderFactory.newInstance();
    xmlFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    DocumentBuilder builder = xmlFactory.newDocumentBuilder();
    var document =
        builder.parse(new ByteArrayInputStream(request.getDatiSpecificiServizioRequest()));

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

  private PaDemandPaymentNoticeResponse createPaDemandPaymentNoticeResponse(
      PaymentPositionModel gpsResponse) throws DatatypeConfigurationException {
    var result = factory.createPaDemandPaymentNoticeResponse();
    result.setCompanyName(gpsResponse.getCompanyName());
    result.setOutcome(StOutcome.OK);
    result.setFiscalCodePA(gpsResponse.getFiscalCode());

    CtQrCode ctQrCode = factory.createCtQrCode();
    ctQrCode.setFiscalCode(gpsResponse.getFiscalCode());
    ctQrCode.setNoticeNumber(gpsResponse.getPaymentOption().get(0).getIuv());
    result.setQrCode(ctQrCode);

    result.setOfficeName(gpsResponse.getOfficeName());
    result.setPaymentDescription(gpsResponse.getPaymentOption().get(0).getDescription());
    CtPaymentOptionsDescriptionListPA ctPaymentOptionsDescriptionListPA =
        factory.createCtPaymentOptionsDescriptionListPA();

    CtPaymentOptionDescriptionPA ctPaymentOptionDescriptionPA =
        factory.createCtPaymentOptionDescriptionPA();

    var ccp =
        gpsResponse.getPaymentOption().get(0).getTransfer().stream()
            .noneMatch(elem -> elem.getPostalIban() == null || elem.getPostalIban().isBlank());
    ctPaymentOptionDescriptionPA.setAllCCP(ccp);

    ctPaymentOptionDescriptionPA.setAmount(
        BigDecimal.valueOf(gpsResponse.getPaymentOption().get(0).getAmount()));

    var date = gpsResponse.getPaymentOption().get(0).getDueDate();
    ctPaymentOptionDescriptionPA.setDueDate(
        DatatypeFactory.newInstance().newXMLGregorianCalendar(String.valueOf(date)));

    ctPaymentOptionDescriptionPA.setOptions(StAmountOption.EQ);
    ctPaymentOptionDescriptionPA.setDetailDescription(
        gpsResponse.getPaymentOption().get(0).getDescription());
    ctPaymentOptionsDescriptionListPA.setPaymentOptionDescription(ctPaymentOptionDescriptionPA);
    result.setPaymentList(ctPaymentOptionsDescriptionListPA);
    return result;
  }

  /**
   * Verify debt position status
   *
   * @param paymentOption {@link PaymentsModelResponse} response from GPD
   */
  private void checkDebtPositionStatus(PaymentsModelResponse paymentOption) {
    String iuvLog = " [iuv=" + paymentOption.getIuv() + "]";
    if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.EXPIRED)) {
      log.error(DEBT_POSITION_STATUS_ERROR + paymentOption.getDebtPositionStatus() + iuvLog);
      throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCADUTO);
    } else if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.INVALID)) {
      log.error(DEBT_POSITION_STATUS_ERROR + paymentOption.getDebtPositionStatus() + iuvLog);
      throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_ANNULLATO);
    } else if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.DRAFT)
        || paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.PUBLISHED)) {
      log.error(DEBT_POSITION_STATUS_ERROR + paymentOption.getDebtPositionStatus() + iuvLog);
      throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
    } else if (paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.PARTIALLY_PAID)
        || paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.PAID)
        || paymentOption.getDebtPositionStatus().equals(DebtPositionStatus.REPORTED)) {
      log.error(DEBT_POSITION_STATUS_ERROR + paymentOption.getDebtPositionStatus() + iuvLog);
      throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_DUPLICATO);
    }
  }

  /**
   * map the response of GPD in the XML model
   *
   * @param source {@link PaymentsModelResponse} response from GPD
   * @param request SOAP input model
   * @return XML model
   * @throws DatatypeConfigurationException If the DatatypeFactory is not available or cannot be
   *     instantiated.
   */
  private PaGetPaymentRes generatePaGetPaymentResponse(
      PaymentsModelResponse source, PaGetPaymentReq request) throws DatatypeConfigurationException {

    PaGetPaymentRes response = factory.createPaGetPaymentRes();
    CtPaymentPA responseData = factory.createCtPaymentPA();
    CtTransferListPA transferList = factory.createCtTransferListPA();

    response.setOutcome(StOutcome.OK);

    // general payment data
    responseData.setCreditorReferenceId(
        request.getQrCode().getNoticeNumber().substring(1)); // set IUV from notice number request
    responseData.setPaymentAmount(BigDecimal.valueOf(source.getAmount()));
    responseData.setDueDate(
        DatatypeFactory.newInstance().newXMLGregorianCalendar(source.getDueDate().toString()));
    responseData.setRetentionDate(
        source.getRetentionDate() != null
            ? DatatypeFactory.newInstance()
                .newXMLGregorianCalendar(source.getRetentionDate().toString())
            : null);
    responseData.setLastPayment(false); // de-scoping
    responseData.setDescription(source.getDescription());
    responseData.setCompanyName(Optional.ofNullable(source.getCompanyName()).orElse("NA"));
    responseData.setOfficeName(Optional.ofNullable(source.getOfficeName()).orElse(("NA")));

    CtSubject debtor = this.getDebtor(source);

    // Transfer list
    transferList
        .getTransfer()
        .addAll(
            source.getTransfer().stream()
                .map(
                    paymentsTransferModelResponse ->
                        getTransferResponse(
                            paymentsTransferModelResponse, request.getTransferType()))
                .collect(Collectors.toList()));

    responseData.setTransferList(transferList);
    responseData.setDebtor(debtor);
    response.setData(responseData);

    return response;
  }

  /**
   * map the response of GPD in the XML V2 model
   *
   * @param source {@link PaymentsModelResponse} response from GPD
   * @param request SOAP input model
   * @return XML model
   * @throws DatatypeConfigurationException If the DatatypeFactory is not available or cannot be
   *     instantiated.
   */
  private PaGetPaymentV2Response generatePaGetPaymentResponse(
      PaymentsModelResponse source, PaGetPaymentV2Request request)
      throws DatatypeConfigurationException {

    PaGetPaymentV2Response response = factory.createPaGetPaymentV2Response();
    CtPaymentPAV2 responseData = factory.createCtPaymentPAV2();
    CtTransferListPAV2 transferList = factory.createCtTransferListPAV2();

    response.setOutcome(StOutcome.OK);

    // general payment data
    responseData.setCreditorReferenceId(
        request.getQrCode().getNoticeNumber().substring(1)); // set IUV from notice number request
    responseData.setPaymentAmount(BigDecimal.valueOf(source.getAmount()));
    responseData.setDueDate(
        DatatypeFactory.newInstance().newXMLGregorianCalendar(source.getDueDate().toString()));
    responseData.setRetentionDate(
        source.getRetentionDate() != null
            ? DatatypeFactory.newInstance()
                .newXMLGregorianCalendar(source.getRetentionDate().toString())
            : null);
    responseData.setLastPayment(false); // de-scoping
    responseData.setDescription(source.getDescription());
    responseData.setCompanyName(Optional.ofNullable(source.getCompanyName()).orElse("NA"));
    responseData.setOfficeName(Optional.ofNullable(source.getOfficeName()).orElse(("NA")));

    // debtor data
    CtSubject debtor = this.getDebtor(source);

    // Transfer list
    transferList
        .getTransfer()
        .addAll(
            source.getTransfer().stream()
                .map(
                    paymentsTransferModelResponse ->
                        getTransferResponseV2(
                            paymentsTransferModelResponse, request.getTransferType()))
                .collect(Collectors.toList()));

    responseData.setTransferList(transferList);
    responseData.setDebtor(debtor);
    response.setData(responseData);

    return response;
  }

  /**
   * map the response of GPD in the XML model
   *
   * @param source {@link PaymentsModelResponse} response from GPD
   * @return XML model
   * @throws DatatypeConfigurationException If the DatatypeFactory is not available or cannot be
   *     instantiated.
   */
  private PaVerifyPaymentNoticeRes generatePaVerifyPaymentNoticeResponse(
      PaymentsModelResponse source) throws DatatypeConfigurationException {

    PaVerifyPaymentNoticeRes result = factory.createPaVerifyPaymentNoticeRes();
    CtPaymentOptionsDescriptionListPA paymentList =
        factory.createCtPaymentOptionsDescriptionListPA();
    CtPaymentOptionDescriptionPA paymentOption = factory.createCtPaymentOptionDescriptionPA();
    // generare una paVerifyPaymentNoticeRes positiva
    result.setOutcome(StOutcome.OK);
    // paymentList
    paymentOption.setAmount(BigDecimal.valueOf(source.getAmount()));
    paymentOption.setOptions(StAmountOption.EQ); // de-scoping
    paymentOption.setDueDate(
        DatatypeFactory.newInstance().newXMLGregorianCalendar(source.getDueDate().toString()));
    paymentOption.setDetailDescription(source.getDescription());
    var cpp =
        source.getTransfer().stream()
            .noneMatch(elem -> elem.getPostalIban() == null || elem.getPostalIban().isBlank());
    paymentOption.setAllCCP(cpp); // allCPP fa parte del modello del option
    paymentList.setPaymentOptionDescription(paymentOption);

    result.setPaymentList(paymentList);
    // general info
    result.setPaymentDescription(source.getDescription());
    result.setFiscalCodePA(source.getOrganizationFiscalCode());
    result.setCompanyName(Optional.ofNullable(source.getCompanyName()).orElse("NA"));
    result.setOfficeName(Optional.ofNullable(source.getOfficeName()).orElse(("NA")));
    return result;
  }

  /**
   * @param transfer GPD response
   * @param transferType XML request
   * @return maps input into {@link CtTransferPA} model
   */
  private CtTransferPA getTransferResponse(
      PaymentsTransferModelResponse transfer, StTransferType transferType) {
    CtTransferPA transferPa = new CtTransferPA();
    transferPa.setFiscalCodePA(transfer.getOrganizationFiscalCode());
    transferPa.setIBAN(getIbanByTransferType(transferType, transfer));
    transferPa.setIdTransfer(Integer.parseInt(transfer.getIdTransfer()));
    transferPa.setRemittanceInformation(transfer.getRemittanceInformation());
    transferPa.setTransferAmount(BigDecimal.valueOf(transfer.getAmount()));
    transferPa.setTransferCategory(transfer.getCategory().replace("/", ""));
    return transferPa;
  }

  /**
   * @param transfer GPD response
   * @param transferType V2 XML request
   * @return maps input into {@link CtTransferPA} model
   */
  private CtTransferPAV2 getTransferResponseV2(
      PaymentsTransferModelResponse transfer, StTransferType transferType) {
    CtRichiestaMarcaDaBollo richiestaMarcaDaBollo =
        customizedModelMapper.map(transfer.getStamp(), CtRichiestaMarcaDaBollo.class);
    CtTransferPAV2 transferPa = new CtTransferPAV2();
    transferPa.setFiscalCodePA(transfer.getOrganizationFiscalCode());
    transferPa.setIBAN(getIbanByTransferType(transferType, transfer));
    transferPa.setRichiestaMarcaDaBollo(richiestaMarcaDaBollo);
    transferPa.setIdTransfer(Integer.parseInt(transfer.getIdTransfer()));
    transferPa.setRemittanceInformation(transfer.getRemittanceInformation());
    transferPa.setTransferAmount(BigDecimal.valueOf(transfer.getAmount()));
    transferPa.setTransferCategory(transfer.getCategory().replace("/", ""));
    return transferPa;
  }

  /**
   * The method return iban given transferType and transfer, according to
   * https://pagopa.atlassian.net/wiki/spaces/PAG/pages/96403906/paGetPayment#trasferType
   */
  private String getIbanByTransferType(
      StTransferType transferType, PaymentsTransferModelResponse transfer) {

    String defaultIban =
        Optional.ofNullable(transfer.getIban())
            .orElseGet(() -> Optional.ofNullable(transfer.getPostalIban()).orElseGet(() -> null));

    return transferType != null
            && transferType.value().equals(StTransferType.POSTAL.value())
            && transfer.getPostalIban() != null
        ? transfer.getPostalIban()
        : defaultIban;
  }

  private PaSendRTRes generatePaSendRTResponse() {
    PaSendRTRes result = factory.createPaSendRTRes();
    result.setOutcome(StOutcome.OK);
    return result;
  }

  private PaSendRTV2Response generatePaSendRTV2Response() {
    PaSendRTV2Response result = factory.createPaSendRTV2Response();
    result.setOutcome(StOutcome.OK);
    return result;
  }

  private String marshal(PaSendRTReq paSendRTReq) throws JAXBException {
    StringWriter sw = new StringWriter();
    JAXBContext context = JAXBContext.newInstance(PaSendRTReq.class);
    Marshaller mar = context.createMarshaller();
    mar.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
    JAXBElement<PaSendRTReq> jaxbElement =
        new JAXBElement<>(new QName("", "paSendRTReq"), PaSendRTReq.class, paSendRTReq);
    mar.marshal(jaxbElement, sw);
    return sw.toString();
  }

  private String marshalV2(PaSendRTV2Request paSendRTV2Request) throws JAXBException {
    StringWriter sw = new StringWriter();
    JAXBContext context = JAXBContext.newInstance(PaSendRTV2Request.class);
    Marshaller mar = context.createMarshaller();
    mar.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
    JAXBElement<PaSendRTV2Request> jaxbElement =
        new JAXBElement<>(
            new QName("", "PaSendRTV2Request"), PaSendRTV2Request.class, paSendRTV2Request);
    mar.marshal(jaxbElement, sw);
    return sw.toString();
  }

  private void saveReceipt(ReceiptEntity receiptEntity)
      throws InvalidKeyException, URISyntaxException, StorageException {
    try {
      TableEntity tableEntity = new TableEntity(receiptEntity.getOrganizationFiscalCode(), receiptEntity.getIuv());
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, receiptEntity.getDebtor());
      properties.put(DOCUMENT_PROPERTY, receiptEntity.getDocument());
      properties.put(STATUS_PROPERTY, receiptEntity.getStatus());
      properties.put(PAYMENT_DATE_PROPERTY, receiptEntity.getPaymentDateTime());
      tableEntity.setProperties(properties);
      tableClient.createEntity(tableEntity);
    } catch (TableServiceException e) {
      log.error(DBERROR, e);
      if(e.getValue().getErrorCode() == TableErrorCode.ENTITY_ALREADY_EXISTS)
      {
        throw new AppException(AppError.RECEIPT_CONFLICT);
      }
      throw new AppException(AppError.DB_ERROR);
    }
  }

  private ReceiptEntity getReceipt(String organizationFiscalCode, String iuv)
      throws InvalidKeyException, URISyntaxException, StorageException {
    try{
      TableEntity tableEntity = tableClient.getEntity(organizationFiscalCode, iuv);
      return ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(tableEntity);
    } catch (TableServiceException e) {
      log.error(DBERROR, e);
      throw new AppException(AppError.DB_ERROR);
    }
  }

  private void updateReceipt(ReceiptEntity receiptEntity)
      throws InvalidKeyException, URISyntaxException, StorageException {
    try {
      TableEntity tableEntity = tableClient.getEntity(receiptEntity.getOrganizationFiscalCode(), receiptEntity.getIuv());
      Map<String, Object> properties = new HashMap<>();
      properties.put(DEBTOR_PROPERTY, receiptEntity.getDebtor());
      properties.put(DOCUMENT_PROPERTY, receiptEntity.getDocument());
      properties.put(STATUS_PROPERTY, receiptEntity.getStatus());
      properties.put(PAYMENT_DATE_PROPERTY, receiptEntity.getPaymentDateTime());
      tableClient.updateEntity(tableEntity);
    } catch (TableServiceException e) {
      log.error(DBERROR, e);
      throw new AppException(AppError.DB_ERROR);
    }
  }

  private long getFeeInCent(BigDecimal fee) {
    long feeInCent = 0;
    if (null != fee) {
      feeInCent = fee.multiply(BigDecimal.valueOf(100)).longValue();
    }
    return feeInCent;
  }

  private CtSubject getDebtor(PaymentsModelResponse source) {

    CtEntityUniqueIdentifier uniqueIdentifier = factory.createCtEntityUniqueIdentifier();
    CtSubject debtor = factory.createCtSubject();

    uniqueIdentifier.setEntityUniqueIdentifierType(
        StEntityUniqueIdentifierType.fromValue(source.getType().name()));
    uniqueIdentifier.setEntityUniqueIdentifierValue(source.getFiscalCode());

    debtor.setUniqueIdentifier(uniqueIdentifier);
    debtor.setFullName(source.getFullName());
    // optional fields --> before the set it is checked that the field is not null and not empty
    Optional.ofNullable(source.getStreetName())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setStreetName);
    Optional.ofNullable(source.getCivicNumber())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setCivicNumber);
    Optional.ofNullable(source.getPostalCode())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setPostalCode);
    Optional.ofNullable(source.getCity())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setCity);
    Optional.ofNullable(source.getProvince())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setStateProvinceRegion);
    Optional.ofNullable(source.getCountry())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setCountry);
    Optional.ofNullable(source.getEmail())
        .filter(Predicate.not(String::isEmpty))
        .ifPresent(debtor::setEMail);

    return debtor;
  }

  private PaymentsModelResponse manageGetPaymentRequest(
      String idPa, String idBrokerPa, String idStation, CtQrCode qrCode) {
    log.debug(
        "[manageGetPaymentRequest] isAuthorize check [noticeNumber={}]", qrCode.getNoticeNumber());
    paymentValidator.isAuthorize(idPa, idBrokerPa, idStation);

    log.debug(
        "[manageGetPaymentRequest] get payment option [noticeNumber={}]", qrCode.getNoticeNumber());
    PaymentsModelResponse paymentOption = null;

    try {
      // with Aux-Digit = 3
      // notice number format is define as follows:
      // 3<segregation code(2n)><IUV base(13n)><IUV check digit(2n)>
      // GPD service works on IUVs directly, so we remove the Aux-Digit
      paymentOption = gpdClient.getPaymentOption(idPa, qrCode.getNoticeNumber().substring(1));
    } catch (FeignException.NotFound e) {
      log.error(
          "[manageGetPaymentRequest] GPD Error not found [noticeNumber={}]",
          qrCode.getNoticeNumber(),
          e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_PAGAMENTO_SCONOSCIUTO);
    } catch (Exception e) {
      log.error(
          "[manageGetPaymentRequest] GPD Generic Error [noticeNumber={}]",
          qrCode.getNoticeNumber(),
          e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
    }
    checkDebtPositionStatus(paymentOption);
    return paymentOption;
  }

  private PaymentOptionModelResponse managePaSendRtRequest(PaSendRTReq request) {
    log.debug(
        "[managePaSendRtRequest] isAuthorize check [noticeNumber={}]",
        request.getReceipt().getNoticeNumber());
    paymentValidator.isAuthorize(
        request.getIdPA(), request.getIdBrokerPA(), request.getIdStation());
    ReceiptEntity receiptEntity =
        this.getReceiptEntity(
            request.getIdPA(),
            request.getReceipt().getCreditorReferenceId(),
            request.getReceipt().getDebtor(),
            request.getReceipt().getPaymentDateTime().toString());
    // save the receipt info with status CREATED
    try {
      receiptEntity.setDocument(this.marshal(request));
      this.saveReceipt(receiptEntity);
    } catch (InvalidKeyException | URISyntaxException | StorageException | JAXBException e) {
      log.error(
          "[managePaSendRtRequest] Generic Error in receipt saving [noticeNumber={}]",
          request.getReceipt().getNoticeNumber(),
          e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
    }

    // GPD service works on IUVs directly, so we use creditorReferenceId (=IUV)
    LocalDateTime paymentDateTime =
        request.getReceipt().getPaymentDateTime() != null
            ? request
                .getReceipt()
                .getPaymentDateTime()
                .toGregorianCalendar()
                .toZonedDateTime()
                .toLocalDateTime()
            : null;
    PaymentOptionModel body =
        PaymentOptionModel.builder()
            .idReceipt(request.getReceipt().getReceiptId())
            .paymentDate(paymentDateTime)
            .pspCompany(request.getReceipt().getPSPCompanyName())
            .paymentMethod(request.getReceipt().getPaymentMethod())
            .fee(String.valueOf(this.getFeeInCent(request.getReceipt().getFee())))
            .build();
    return this.getReceiptPaymentOption(
        request.getReceipt().getNoticeNumber(),
        request.getIdPA(),
        request.getReceipt().getCreditorReferenceId(),
        body,
        receiptEntity);
  }

  private PaymentOptionModelResponse managePaSendRtRequest(PaSendRTV2Request request) {
    log.debug(
        "[managePaSendRtRequest] isAuthorize check [noticeNumber={}]",
        request.getReceipt().getNoticeNumber());
    paymentValidator.isAuthorize(
        request.getIdPA(), request.getIdBrokerPA(), request.getIdStation());

    ReceiptEntity receiptEntity =
        this.getReceiptEntity(
            request.getIdPA(),
            request.getReceipt().getCreditorReferenceId(),
            request.getReceipt().getDebtor(),
            request.getReceipt().getPaymentDateTime().toString());
    // save the receipt info with status CREATED
    try {
      receiptEntity.setDocument(this.marshalV2(request));
      this.saveReceipt(receiptEntity);
    } catch (InvalidKeyException | URISyntaxException | StorageException | JAXBException e) {
      log.error(
          "[managePaSendRtRequest] Generic Error in receipt saving [noticeNumber={}]",
          request.getReceipt().getNoticeNumber(),
          e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
    }

    // GPD service works on IUVs directly, so we use creditorReferenceId (=IUV)
    LocalDateTime paymentDateTime =
        request.getReceipt().getPaymentDateTime() != null
            ? request
                .getReceipt()
                .getPaymentDateTime()
                .toGregorianCalendar()
                .toZonedDateTime()
                .toLocalDateTime()
            : null;
    PaymentOptionModel body =
        PaymentOptionModel.builder()
            .idReceipt(request.getReceipt().getReceiptId())
            .paymentDate(paymentDateTime)
            .pspCompany(request.getReceipt().getPSPCompanyName())
            .paymentMethod(request.getReceipt().getPaymentMethod())
            .fee(String.valueOf(this.getFeeInCent(request.getReceipt().getFee())))
            .build();
    return this.getReceiptPaymentOption(
        request.getReceipt().getNoticeNumber(),
        request.getIdPA(),
        request.getReceipt().getCreditorReferenceId(),
        body,
        receiptEntity);
  }

  private ReceiptEntity getReceiptEntity(
      String idPa, String creditorReferenceId, CtSubject debtor, String paymentDateTime) {
    ReceiptEntity receiptEntity = new ReceiptEntity(idPa, creditorReferenceId);
    String debtorIdentifier =
        Optional.ofNullable(debtor)
            .map(CtSubject::getUniqueIdentifier)
            .map(CtEntityUniqueIdentifier::getEntityUniqueIdentifierValue)
            .orElse("");
    receiptEntity.setDebtor(debtorIdentifier);
    receiptEntity.setPaymentDateTime(paymentDateTime);
    return receiptEntity;
  }

  private PaymentOptionModelResponse getReceiptPaymentOption(
      String noticeNumber,
      String idPa,
      String creditorReferenceId,
      PaymentOptionModel body,
      ReceiptEntity receiptEntity) {
    PaymentOptionModelResponse paymentOption = new PaymentOptionModelResponse();
    try {
      paymentOption = gpdClient.receiptPaymentOption(idPa, creditorReferenceId, body);
      // update receipt status to PAID
      if (PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus())) {
        receiptEntity.setStatus(Status.PAID.name());
        this.updateReceipt(receiptEntity);
      }
    } catch (FeignException.Conflict e) {
      try {
        log.error(
            "[getReceiptPaymentOption] GPD Conflict Error Response [noticeNumber={}]",
            noticeNumber,
            e);
        ReceiptEntity receiptEntityToUpdate = this.getReceipt(idPa, creditorReferenceId);
        receiptEntityToUpdate.setStatus(Status.PAID.name());
        this.updateReceipt(receiptEntityToUpdate);
      } catch (Exception ex) {
        log.error(
            "[getReceiptPaymentOption] GPD Generic Error [noticeNumber={}] during receipt status"
                + " update",
            noticeNumber,
            e);
      }
      throw new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA);
    } catch (RetryableException e) {
      log.error("[getReceiptPaymentOption] GPD Not Reachable [noticeNumber={}]", noticeNumber, e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
    } catch (FeignException e) {
      log.error("[getReceiptPaymentOption] GPD Error Response [noticeNumber={}]", noticeNumber, e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SEMANTICA);
    } catch (Exception e) {
      log.error("[getReceiptPaymentOption] GPD Generic Error [noticeNumber={}]", noticeNumber, e);
      throw new PartnerValidationException(PaaErrorEnum.PAA_SYSTEM_ERROR);
    }
    return paymentOption;
  }
}
