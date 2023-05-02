package it.gov.pagopa.payments.mapper;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.entity.ReceiptEntity;

public class ConvertTableEntityToReceiptEntity {
    public final static String DEBTOR_PROPERTY = "debtor";
    public final static String DOCUMENT_PROPERTY = "document";
    public final static String STATUS_PROPERTY = "status";
    public final static String PAYMENT_DATE_PROPERTY = "paymentDate";

    public static ReceiptEntity mapTableEntityToReceiptEntity(TableEntity tableEntity) {
        ReceiptEntity observation = new ReceiptEntity(
                tableEntity.getPartitionKey(), tableEntity.getRowKey(),
                tableEntity.getProperty(DEBTOR_PROPERTY).toString(),
                tableEntity.getProperty(PAYMENT_DATE_PROPERTY).toString(),
                tableEntity.getProperty(STATUS_PROPERTY).toString(),
                tableEntity.getProperty(DOCUMENT_PROPERTY).toString());
        return observation;
    }
}
