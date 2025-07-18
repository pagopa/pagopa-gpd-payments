package it.gov.pagopa.payments.service;

import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.microsoft.azure.storage.StorageException;
import feign.FeignException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.service.primitive.ReceiveRTService;
import it.gov.pagopa.payments.utils.CommonUtil;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerService {

    @Value(value = "${azure.queue.dequeue.limit}")
    private Integer dequeueLimit;

    @Value(value = "${azure.queue.receive.invisibilityTime}")
    private Long queueReceiveInvisibilityTime;

    @Value(value = "${azure.queue.send.invisibilityTime}")
    private Long queueUpdateInvisibilityTime;

    @Autowired
    QueueClient queueClient;

    @Autowired
    ReceiveRTService receiveRtService;

    public void retryFailedPaSendRT() {
        XPathFactory xPathfactory = XPathFactory.newInstance();
        XPath xpath = xPathfactory.newXPath();
        // The message is dequeued and locked for a timeout equal to <queueReceiveInvisibilityTime> seconds
        List<QueueMessageItem> queueList = queueClient.receiveMessages(
                10,
                Duration.ofSeconds(queueReceiveInvisibilityTime),
                null,
                Context.NONE)
                .stream().toList();
        for(QueueMessageItem message: queueList) {
            if(checkQueueCountValidity(message)) {
                try{
                    handlingXml(getMessageContent(message), xpath, message);
                } catch (XPathExpressionException e) {
                    log.error("[paSendRT] XML error during retry process [messageId={},popReceipt={}]\",\n", message.getMessageId() , message.getPopReceipt());
                }
            } else {
                queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
            }
        }
    }

    /*
    This method is used to parse the xml string and extract the necessary values to create a payment option body.
    After this, the payment process is triggered with the newly created body.
     */
    public void handlingXml (String failureBody, XPath xpath, QueueMessageItem queueMessageItem) throws XPathExpressionException {
        Document xmlDoc = getXMLDocument(failureBody);
        XPathExpression xPathExpr = xpath.compile('/' + xmlDoc.getFirstChild().getNodeName());
        NodeList nodes = (NodeList) xPathExpr.evaluate(xmlDoc, XPathConstants.NODESET);

        Element node = (Element) nodes.item(0);

        String idPA = node.getElementsByTagName("idPA").item(0).getTextContent();
        String creditorReferenceId = node.getElementsByTagName("creditorReferenceId").item(0).getTextContent();
        String noticeNumber = node.getElementsByTagName("noticeNumber").item(0).getTextContent();
        String paymentDateTime = node.getElementsByTagName("paymentDateTime").item(0).getTextContent();
        String receiptId = node.getElementsByTagName("receiptId").item(0).getTextContent();
        String PSPCompanyName = node.getElementsByTagName("PSPCompanyName").item(0).getTextContent();
        String paymentMethod = node.getElementsByTagName("paymentMethod").item(0).getTextContent();
        String fee = node.getElementsByTagName("fee").item(0).getTextContent();
        String entityUniqueIdentifierValue = node.getElementsByTagName("entityUniqueIdentifierValue").item(0).getTextContent();

        String standInString = "false";
        NodeList standInNodeList = node.getElementsByTagName("standIn");
        if(standInNodeList.getLength() > 0) {
            standInString = standInNodeList.item(0).getTextContent();
        }

        ReceiptEntity receiptEntity = new ReceiptEntity(idPA, creditorReferenceId);
        receiptEntity.setDebtor(entityUniqueIdentifierValue);
        String paymentDateTimeIdentifier = Optional.ofNullable(paymentDateTime).orElse("");
        receiptEntity.setPaymentDateTime(paymentDateTimeIdentifier);
        receiptEntity.setDocument(failureBody);

        LocalDateTime localPaymentDateTime = paymentDateTime != null ? LocalDateTime.parse(paymentDateTime) : null;
        PaymentOptionModel body =
                PaymentOptionModel.builder()
                        .idReceipt(receiptId)
                        .paymentDate(localPaymentDateTime)
                        .pspCompany(PSPCompanyName)
                        .paymentMethod(paymentMethod)
                        .fee(String.valueOf(CommonUtil.getFeeInCent(new BigDecimal(fee))))
                        .build();

        try {
            receiveRtService.setPaidPaymentOption(
                    noticeNumber,
                    idPA,
                    creditorReferenceId,
                    Boolean.parseBoolean(standInString),
                    body,
                    receiptEntity);
            queueClient.deleteMessage(queueMessageItem.getMessageId(), queueMessageItem.getPopReceipt());
        } catch (FeignException | URISyntaxException | InvalidKeyException | StorageException e) {
            log.debug("[paSendRT] Retry failed [fiscalCode={},noticeNumber={}]\",\n", idPA, noticeNumber);
            queueClient.updateMessageWithResponse(
                    queueMessageItem.getMessageId(),
                    queueMessageItem.getPopReceipt(),
                    receiptEntity.getDocument(),
                    Duration.ofSeconds(queueUpdateInvisibilityTime),
                    null,
                    Context.NONE);
        } catch (PartnerValidationException e) {
            // { PAA_RECEIPT_DUPLICATA, PAA_PAGAMENTO_SCONOSCIUTO }
            log.warn("[paSendRT] Retry failed {} [fiscalCode={},noticeNumber={}]\",\n", e.getMessage(), idPA, noticeNumber);
            queueClient.deleteMessage(queueMessageItem.getMessageId(), queueMessageItem.getPopReceipt());
        }
    }

    public boolean checkQueueCountValidity(QueueMessageItem message) {
        return message.getDequeueCount() <= dequeueLimit;
    }

    public String getMessageContent(QueueMessageItem queueMessageItem){
        return new String(queueMessageItem.getBody().toBytes(), StandardCharsets.UTF_8);
    }

    public Document getXMLDocument(String xmlString) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource is = new InputSource(new StringReader(xmlString));
            return builder.parse(is);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AppException(AppError.UNKNOWN);
        }
    }
}
