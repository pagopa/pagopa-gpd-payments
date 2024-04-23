package it.gov.pagopa.payments.service;

import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.microsoft.azure.storage.StorageException;
import feign.FeignException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

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
public class SchedulerService {

    @Value(value = "${azure.queue.dequeue.limit}")
    private Integer dequeueLimit;
    @Autowired
    QueueClient queueClient;

    @Autowired
    PartnerService partnerService;

    public void getAllFailuresQueue() {
        XPathFactory xPathfactory = XPathFactory.newInstance();
        XPath xpath = xPathfactory.newXPath();
        try {
            // The message is dequeued and locked for 30 seconds by default
            List<QueueMessageItem> queueList = queueClient.receiveMessages(10, Duration.ofMinutes(5L), null, Context.NONE).stream().toList();
            for(QueueMessageItem message: queueList) {
                if(checkQueueCountValidity(message)) {
                    handlingXml(getFailureQueue(message), xpath, message);
                } else {
                    queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
                }
            }
        } catch (XPathExpressionException e) {

        }
    }

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
                        .fee(String.valueOf(partnerService.getFeeInCent(new BigDecimal(fee))))
                        .build();

        try {
            partnerService.getReceiptPaymentOption(
                    noticeNumber,
                    idPA,
                    creditorReferenceId,
                    body,
                    receiptEntity);
        } catch (FeignException | URISyntaxException | InvalidKeyException | StorageException e) {
            log.info("[paSendRT] Retry failed [fiscalCode={},noticeNumber={}]\",\n", idPA, noticeNumber);
            queueClient.updateMessageWithResponse(queueMessageItem.getMessageId(), queueMessageItem.getPopReceipt(), receiptEntity.getDocument(), Duration.ofMinutes(10L), null, Context.NONE);
        }
    }

    public boolean checkQueueCountValidity(QueueMessageItem message) {
        return message.getDequeueCount() <= dequeueLimit;
    }

    public String getFailureQueue(QueueMessageItem queueMessageItem){
        return new String(queueMessageItem.getBody().toBytes(), StandardCharsets.UTF_8);
    }
    public Document getXMLDocument(String xmlString) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            InputSource is = new InputSource(new StringReader(xmlString));
            return builder.parse(is);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new AppException(AppError.UNKNOWN);
        }
    }
}
