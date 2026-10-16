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
import it.gov.pagopa.payments.model.DeadLetterMessage;
import it.gov.pagopa.payments.model.PaymentOptionModel;
import it.gov.pagopa.payments.model.enumeration.DeadLetterReason;
import it.gov.pagopa.payments.logging.ContextSnapshot;
import it.gov.pagopa.payments.logging.EventDetails;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.LogEvent;
import it.gov.pagopa.payments.logging.OperationLogScope;
import lombok.extern.slf4j.Slf4j;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

import static it.gov.pagopa.payments.logging.EventDetails.Dependency.AZURE_QUEUE_STORAGE;
import static it.gov.pagopa.payments.logging.EventDetails.Dependency.GPD_CORE;
import static it.gov.pagopa.payments.logging.EventDetails.DependencyOperation.*;

@Service
@Slf4j
public class SchedulerService {

    private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);

    private enum Disposition { PROCESSED, RETRY_SCHEDULED, DISCARDED, DEAD_LETTERED, FAILED }

    /** Per-invocation counters survive a batch abort without retaining state on the singleton. */
    public static final class JobCounts {
        private long processed;
        private long retryScheduled;
        private long discarded;
        private long deadLettered;
        private long failed;

        private void record(Disposition disposition) {
            switch (disposition) {
                case PROCESSED -> processed++;
                case RETRY_SCHEDULED -> retryScheduled++;
                case DISCARDED -> discarded++;
                case DEAD_LETTERED -> deadLettered++;
                case FAILED -> failed++;
            }
        }

        public EventDetails.JobSummary summary() {
            return new EventDetails.JobSummary(
                    processed, retryScheduled, discarded, deadLettered, failed);
        }
    }

	private final Integer dequeueLimit;
	private final Long queueReceiveInvisibilityTime;
	private final Long queueRetryInitialDelay;
	private final Long queueRetryMaxDelay;
	private final Double queueRetryMultiplier;

	private final QueueClient queueClient;
	private final PartnerService partnerService;
	private final DeadLetterService deadLetterService;

	public SchedulerService(
	        @Value("${azure.queue.dequeue.limit}") Integer dequeueLimit,
	        @Value("${azure.queue.receive.invisibilityTime}") Long queueReceiveInvisibilityTime,
	        @Value("${azure.queue.retry.initialDelay}") Long queueRetryInitialDelay,
	        @Value("${azure.queue.retry.maxDelay}") Long queueRetryMaxDelay,
	        @Value("${azure.queue.retry.multiplier}") Double queueRetryMultiplier,
	        QueueClient queueClient,
	        PartnerService partnerService,
	        DeadLetterService deadLetterService) {

	    this.dequeueLimit = dequeueLimit;
	    this.queueReceiveInvisibilityTime = queueReceiveInvisibilityTime;
	    this.queueRetryInitialDelay = queueRetryInitialDelay;
	    this.queueRetryMaxDelay = queueRetryMaxDelay;
	    this.queueRetryMultiplier = queueRetryMultiplier;
	    this.queueClient = queueClient;
	    this.partnerService = partnerService;
	    this.deadLetterService = deadLetterService;
	}

    public void retryFailedPaSendRT() {
        retryFailedPaSendRT(new JobCounts());
    }

    public void retryFailedPaSendRT(JobCounts counts) {
        XPathFactory xPathfactory = XPathFactory.newInstance();
        XPath xpath = xPathfactory.newXPath();
        // The message is dequeued and locked for a timeout equal to <queueReceiveInvisibilityTime> seconds
        List<QueueMessageItem> queueList;
        long started = System.nanoTime();
        try {
            queueList = queueClient.receiveMessages(
                10,
                Duration.ofSeconds(queueReceiveInvisibilityTime),
                null,
                Context.NONE)
                .stream().toList();
        } catch (RuntimeException e) {
            FailureDiagnostics.dependencyFailure(
                    AZURE_QUEUE_STORAGE, RECEIVE_RECEIPT_RETRIES, started, e);
            FailureDiagnostics.technicalFailure(LOG_EVENTS, e);
            throw e;
        }
        String jobRunId = MDC.get("jobRunId");
        if (jobRunId == null) {
            jobRunId = UUID.randomUUID().toString();
        }
        for(QueueMessageItem message: queueList) {
            try (OperationLogScope ignored = new ContextSnapshot(
                    null, null, jobRunId, message.getMessageId(), null, null)
                    .openOperation("retryReceipt")) {
                try {
                    Disposition disposition = checkQueueCountValidity(message)
                            ? processXml(getMessageContent(message), xpath, message)
                            : moveToDeadLetter(message);
                    // Reconciliation can absorb a technical failure and still return a business fault.
                    if ((disposition == Disposition.PROCESSED || disposition == Disposition.DISCARDED)
                            && OperationLogScope.current().hasDiagnosis()) {
                        disposition = Disposition.FAILED;
                    }
                    counts.record(disposition);
                } catch (XPathExpressionException e) {
                    counts.record(Disposition.FAILED);
                    FailureDiagnostics.absorbedFailure(LOG_EVENTS, e);
                } catch (RuntimeException e) {
                    counts.record(Disposition.FAILED);
                    FailureDiagnostics.technicalFailure(LOG_EVENTS, e);
                    throw e;
                }
            }
        }
    }

    /*
    This method is used to parse the xml string and extract the necessary values to create a payment option body.
    After this, the payment process is triggered with the newly created body.
     */
    public void handlingXml (String failureBody, XPath xpath, QueueMessageItem queueMessageItem) throws XPathExpressionException {
        processXml(failureBody, xpath, queueMessageItem);
    }

    private Disposition processXml(String failureBody, XPath xpath, QueueMessageItem queueMessageItem) throws XPathExpressionException {
        Document xmlDoc = getXMLDocument(failureBody);
        XPathExpression xPathExpr = xpath.compile('/' + xmlDoc.getFirstChild().getNodeName());
        NodeList nodes = (NodeList) xPathExpr.evaluate(xmlDoc, XPathConstants.NODESET);

        Element node = (Element) nodes.item(0);

        String receiptFiscalCode = node.getElementsByTagName("fiscalCode").item(0).getTextContent();
        String creditorReferenceId = node.getElementsByTagName("creditorReferenceId").item(0).getTextContent();
        String noticeNumber = node.getElementsByTagName("noticeNumber").item(0).getTextContent();
        if (OperationLogScope.currentOrNull() != null) {
            OperationLogScope.current().setPaymentLogContext(noticeNumber, receiptFiscalCode);
        }
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

        ReceiptEntity receiptEntity = new ReceiptEntity(receiptFiscalCode, creditorReferenceId);
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
            // This retry coordinator handles Feign recovery; direct PartnerService scheduler calls
            // outside the coordinator do not log an intended recovery.
            partnerService.getReceiptPaymentOptionScheduler(
                    noticeNumber,
                    receiptFiscalCode,
                    creditorReferenceId,
                    Boolean.parseBoolean(standInString),
                    body,
                    receiptEntity);
            deleteMessage(queueMessageItem, null);
            LOG_EVENTS.logEvent(LogEvent.RECEIPT_PROCESSING_COMPLETED, disposition(queueMessageItem, null));
            return Disposition.PROCESSED;
        } catch (FeignException | URISyntaxException | InvalidKeyException | StorageException e) {
            if (!(e instanceof FeignException)) {
                FailureDiagnostics.absorbedFailure(LOG_EVENTS, e);
            }

            long retryDelay =
                    calculateRetryVisibilityTimeout(
                            queueMessageItem.getDequeueCount());

            long started = System.nanoTime();
            try {
                queueClient.updateMessageWithResponse(
                    queueMessageItem.getMessageId(),
                    queueMessageItem.getPopReceipt(),
                    receiptEntity.getDocument(),
                    Duration.ofSeconds(retryDelay),
                    null,
                    Context.NONE);
            } catch (RuntimeException updateFailure) {
                if (updateFailure != e) {
                    updateFailure.addSuppressed(e);
                }
                FailureDiagnostics.dependencyFailure(AZURE_QUEUE_STORAGE,
                        RESCHEDULE_RECEIPT_RETRY, started, updateFailure);
                throw updateFailure;
            }
            LOG_EVENTS.logEvent(LogEvent.RECEIPT_RETRY_SCHEDULED, new EventDetails.Retry(
                    e instanceof FeignException ? GPD_CORE : null, (int) queueMessageItem.getDequeueCount(),
                    Duration.ofSeconds(retryDelay).toMillis(), EventDetails.Reason.RETRYABLE_FAILURE,
                    queueMessageItem.getMessageId()));
            if (e instanceof FeignException) {
                FailureDiagnostics.recovered(e);
            }
            return Disposition.RETRY_SCHEDULED;
        } catch (PartnerValidationException e) {
            // { PAA_RECEIPT_DUPLICATA, PAA_PAGAMENTO_SCONOSCIUTO }
            deleteMessage(queueMessageItem, null);
            String previousFaultCode = MDC.get("faultCode");
            MDC.put("faultCode", e.getError().getFaultCode());
            try {
                LOG_EVENTS.logEvent(LogEvent.RECEIPT_PROCESSING_COMPLETED,
                        disposition(queueMessageItem, EventDetails.Reason.BUSINESS_REJECTION));
            } finally {
                if (previousFaultCode == null) {
                    MDC.remove("faultCode");
                } else {
                    MDC.put("faultCode", previousFaultCode);
                }
            }
            return Disposition.DISCARDED;
        }
    }
    
    long calculateRetryVisibilityTimeout(long dequeueCount) {

        long exponent = Math.max(0L, dequeueCount - 1);

        double calculatedDelay =
                queueRetryInitialDelay
                        * Math.pow(queueRetryMultiplier, exponent);

        /*
         * Cap the exponential backoff to prevent excessively long retry delays
         * when the dequeue count grows.
         * 
         * Without capping (initialDelay = 120s / 2m, multiplier = 2.0):
         * - dequeueCount 1: 120s  (2 min)
         * - dequeueCount 2: 240s  (4 min)
         * - dequeueCount 3: 480s  (8 min)
         * - dequeueCount 4: 960s  (16 min)
         * - dequeueCount 5: 1920s (32 min)
         * - dequeueCount 6: 3840s (64 min / >1 hour)
         * - dequeueCount 10: ~34 hours
         * 
         * With cap (maxDelay = 3600s / 60 min):
         * - dequeueCount 6: 3840s -> Capped at 3600s (60 min)
         * - dequeueCount 7: 7680s -> Capped at 3600s (60 min)
         */
        return Math.min(
                (long) Math.ceil(calculatedDelay),
                queueRetryMaxDelay);
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
            // UNKNOWN has a legacy null template: construction itself may fail independently.
            FailureDiagnostics.absorbedFailure(LOG_EVENTS, e);
            throw new AppException(AppError.UNKNOWN, e);
        }
    }
    
    private Disposition moveToDeadLetter(QueueMessageItem message) {

        DeadLetterMessage deadLetterMessage =
                DeadLetterMessage.builder()
                        .messageId(message.getMessageId())
                        .dequeueCount(message.getDequeueCount())
                        .reason(DeadLetterReason.MAX_RETRY_ATTEMPTS_REACHED)
                        .deadLetteredAt(Instant.now())
                        .originalMessage(getMessageContent(message))
                        .build();

        try {
            /*
             * The retry queue message must be deleted only after the dead-letter
             * message has been successfully persisted. This prevents message loss
             * if Blob Storage is temporarily unavailable.
             */
            deadLetterService.sendToDeadLetter(deadLetterMessage);

            deleteMessage(message, EventDetails.Reason.DEAD_LETTER_PERSISTED_SOURCE_DELETE_FAILED);
            LOG_EVENTS.logEvent(LogEvent.RECEIPT_DEAD_LETTERED,
                    disposition(message, EventDetails.Reason.RETRY_LIMIT_EXCEEDED));
            return Disposition.DEAD_LETTERED;

        } catch (RuntimeException e) {
            /*
             * Keep the message in the retry queue if dead-letter persistence fails.
             * It will become visible again after the queue visibility timeout.
             */
            FailureDiagnostics.absorbedFailure(LOG_EVENTS, e);
            return Disposition.FAILED;
        }
    }

    private void deleteMessage(QueueMessageItem message, EventDetails.Reason reason) {
        long started = System.nanoTime();
        try {
            queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
        } catch (RuntimeException e) {
            FailureDiagnostics.dependencyFailure(AZURE_QUEUE_STORAGE,
                    DELETE_RECEIPT_RETRY, started, e, reason, message.getMessageId());
            throw e;
        }
    }

    private EventDetails.ReceiptDisposition disposition(QueueMessageItem message, EventDetails.Reason reason) {
        return new EventDetails.ReceiptDisposition(
                message.getMessageId(), (int) message.getDequeueCount(), reason);
    }
}
