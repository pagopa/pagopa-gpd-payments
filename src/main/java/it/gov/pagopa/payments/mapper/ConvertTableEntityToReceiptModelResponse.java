package it.gov.pagopa.payments.mapper;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.model.ReceiptModelResponse;

public class ConvertTableEntityToReceiptModelResponse {
    public static final String DEBTOR_PROPERTY = "debtor";
    public static final String STATUS_PROPERTY = "status";
    public static final String PAYMENT_DATE_PROPERTY = "paymentDate";

    public static ReceiptModelResponse mapTableEntityToReceiptModelResponse(TableEntity tableEntity) {
        return ReceiptModelResponse.builder()
                .organizationFiscalCode(tableEntity.getPartitionKey())
                .iuv(tableEntity.getRowKey())
                .debtor(tableEntity.getProperty(DEBTOR_PROPERTY).toString())
                .paymentDateTime(tableEntity.getProperty(PAYMENT_DATE_PROPERTY).toString())
                .status(tableEntity.getProperty(STATUS_PROPERTY).toString())
                .build();
    }
}
