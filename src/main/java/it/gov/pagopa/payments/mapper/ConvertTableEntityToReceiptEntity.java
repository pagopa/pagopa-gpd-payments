package it.gov.pagopa.payments.mapper;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.entity.ReceiptEntity;

public class ConvertTableEntityToReceiptEntity {
  public static final String DEBTOR_PROPERTY = "debtor";
  public static final String DOCUMENT_PROPERTY = "document";
  public static final String STATUS_PROPERTY = "status";
  public static final String PAYMENT_DATE_PROPERTY = "paymentDate";
  public static final String CONTENT_HASH_PROPERTY = "contentHash";

  public static ReceiptEntity mapTableEntityToReceiptEntity(TableEntity tableEntity) {
    return new ReceiptEntity(
        tableEntity.getPartitionKey(),
        tableEntity.getRowKey(),
        tableEntity.getProperty(DEBTOR_PROPERTY).toString(),
        tableEntity.getProperty(PAYMENT_DATE_PROPERTY).toString(),
        tableEntity.getProperty(STATUS_PROPERTY).toString(),
        optionalProperty(tableEntity, DOCUMENT_PROPERTY),
        optionalProperty(tableEntity, CONTENT_HASH_PROPERTY));
  }

  private static String optionalProperty(TableEntity tableEntity, String name) {
    Object value = tableEntity.getProperty(name);
    return value == null ? null : value.toString();
  }
}
