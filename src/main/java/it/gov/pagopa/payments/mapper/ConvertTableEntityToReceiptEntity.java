package it.gov.pagopa.payments.mapper;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.entity.ReceiptEntity;

public class ConvertTableEntityToReceiptEntity {
    public static final String DEBTOR_PROPERTY = "debtor";
    public static final String DOCUMENT_PROPERTY = "document";
    public static final String STATUS_PROPERTY = "status";
    public static final String PAYMENT_DATE_PROPERTY = "paymentDate";

    public static ReceiptEntity mapTableEntityToReceiptEntity(TableEntity tableEntity) {
        return new ReceiptEntity(
                tableEntity.getPartitionKey(), tableEntity.getRowKey(),
                tableEntity.getProperty(DEBTOR_PROPERTY).toString(),
                tableEntity.getProperty(PAYMENT_DATE_PROPERTY).toString(),
                tableEntity.getProperty(STATUS_PROPERTY).toString(),
                tableEntity.getProperty(DOCUMENT_PROPERTY).toString());
    }
}
