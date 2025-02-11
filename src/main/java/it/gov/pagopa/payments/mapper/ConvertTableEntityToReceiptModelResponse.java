package it.gov.pagopa.payments.mapper;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.model.enumeration.ReceiptStatus;

import java.time.LocalDateTime;

public class ConvertTableEntityToReceiptModelResponse {
    public static final String DEBTOR_PROPERTY = "debtor";
    public static final String STATUS_PROPERTY = "status";
    public static final String PAYMENT_DATE_PROPERTY = "paymentDate";

    public static ReceiptModelResponse mapTableEntityToReceiptModelResponse(TableEntity tableEntity) {
        return ReceiptModelResponse.builder()
                .organizationFiscalCode(tableEntity.getPartitionKey())
                .iuv(tableEntity.getRowKey())
                .debtor(tableEntity.getProperty(DEBTOR_PROPERTY).toString())
                .paymentDateTime((LocalDateTime) tableEntity.getProperty(PAYMENT_DATE_PROPERTY))
                .status(ReceiptStatus.valueOf(tableEntity.getProperty(STATUS_PROPERTY).toString()))
                .build();
    }
}
