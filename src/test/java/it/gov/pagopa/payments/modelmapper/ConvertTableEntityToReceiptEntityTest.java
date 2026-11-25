package it.gov.pagopa.payments.modelmapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntity;
import org.junit.jupiter.api.Test;

class ConvertTableEntityToReceiptEntityTest {

  @Test
  void shouldMapTableEntityToReceiptEntity() {
    TableEntity tableEntity = new TableEntity("CF1", "IUV1");
    tableEntity.addProperty("debtor", "debtor1");
    tableEntity.addProperty("paymentDate", "2026-10-01T10:00:00");
    tableEntity.addProperty("status", "PAID");

    ReceiptEntity receiptEntity =
        ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(tableEntity);

    assertThat(receiptEntity.getOrganizationFiscalCode()).isEqualTo("CF1");
    assertThat(receiptEntity.getIuv()).isEqualTo("IUV1");
    assertThat(receiptEntity.getDebtor()).isEqualTo("debtor1");
    assertThat(receiptEntity.getPaymentDateTime()).isEqualTo("2026-10-01T10:00:00");
    assertThat(receiptEntity.getStatus()).isEqualTo("PAID");
  }
}