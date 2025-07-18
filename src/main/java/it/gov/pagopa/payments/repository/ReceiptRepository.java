package it.gov.pagopa.payments.repository;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.payments.endpoints.validation.exceptions.PartnerValidationException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntity;
import it.gov.pagopa.payments.model.PaaErrorEnum;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@NoArgsConstructor
@Service
public class ReceiptRepository {

    public static final String DEBTOR_PROPERTY = "debtor";
    public static final String DOCUMENT_PROPERTY = "document";
    public static final String STATUS_PROPERTY = "status";
    public static final String PAYMENT_DATE_PROPERTY = "paymentDate";
    private static final String DBERROR = "Error in organization table connection";

    @Autowired
    private TableClient tableClient;

    public ReceiptRepository(@Autowired TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public void saveReceipt(ReceiptEntity receiptEntity) {
        try {
            TableEntity tableEntity = getTableEntity(receiptEntity);
            tableClient.createEntity(tableEntity);
        } catch (TableServiceException e) {
            log.error(DBERROR, e);
            if (e.getValue().getErrorCode() == TableErrorCode.ENTITY_ALREADY_EXISTS) {
                throw new PartnerValidationException(PaaErrorEnum.PAA_RECEIPT_DUPLICATA);
            }
            throw new AppException(AppError.DB_ERROR);
        }
    }

    public ReceiptEntity getReceipt(String organizationFiscalCode, String iuv) {
        try {
            TableEntity tableEntity = tableClient.getEntity(organizationFiscalCode, iuv);
            return ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(tableEntity);
        } catch (TableServiceException e) {
            log.error(DBERROR, e);
            if (e.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND) return null;
            else throw new AppException(AppError.DB_ERROR);
        }
    }

    public ReceiptEntity createReceiptEntity(
            String idPa, String creditorReferenceId, String debtor, String paymentDateTime) {
        ReceiptEntity receiptEntity = new ReceiptEntity(idPa, creditorReferenceId);
        receiptEntity.setDebtor(debtor);
        String paymentDateTimeIdentifier = Optional.ofNullable(paymentDateTime).orElse("");
        receiptEntity.setPaymentDateTime(paymentDateTimeIdentifier);
        return receiptEntity;
    }

    private static TableEntity getTableEntity(ReceiptEntity receiptEntity) {
        TableEntity tableEntity =
                new TableEntity(receiptEntity.getOrganizationFiscalCode(), receiptEntity.getIuv());
        Map<String, Object> properties = new HashMap<>();
        properties.put(DEBTOR_PROPERTY, receiptEntity.getDebtor());
        properties.put(DOCUMENT_PROPERTY, receiptEntity.getDocument());
        properties.put(STATUS_PROPERTY, receiptEntity.getStatus());
        properties.put(PAYMENT_DATE_PROPERTY, receiptEntity.getPaymentDateTime());
        tableEntity.setProperties(properties);
        return tableEntity;
    }
}
