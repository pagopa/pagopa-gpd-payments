package it.gov.pagopa.payments.mapper;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.entity.ReceiptEntityCosmos;

public class ConvertTableEntityToReceiptEntityCosmos {
    public final static String DEBTOR_PROPERTY = "debtor";
    public final static String DOCUMENT_PROPERTY = "document";
    public final static String STATUS_PROPERTY = "status";

    public static ReceiptEntityCosmos mapTableEntityToReceiptEntity(TableEntity tableEntity) {
        ReceiptEntityCosmos observation = new ReceiptEntityCosmos(
                tableEntity.getPartitionKey(), tableEntity.getRowKey(),
                tableEntity.getProperty(DEBTOR_PROPERTY).toString(), tableEntity.getProperty(DOCUMENT_PROPERTY).toString(),
                tableEntity.getProperty(STATUS_PROPERTY).toString());
        return observation;
    }
}
