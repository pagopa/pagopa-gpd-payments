package it.gov.pagopa.payments.modelmapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.data.tables.models.TableEntity;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntity;
import org.junit.jupiter.api.Test;

class ConvertTableEntityToReceiptEntityTest {

  private TableEntity base() {
    TableEntity te = new TableEntity("CF1", "IUV1");
    te.addProperty("debtor", "debtor1");
    te.addProperty("paymentDate", "2026-10-01T10:00:00");
    te.addProperty("status", "PAID");
    return te;
  }

  @Test
  void legacyEntityHasDocumentAndNoContentHash() {
    TableEntity te = base();
    te.addProperty("document", "<xml/>");

    ReceiptEntity re = ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(te);

    assertThat(re.getDocument()).isEqualTo("<xml/>");
    assertThat(re.getContentHash()).isNull();
  }

  @Test
  void migratedEntityHasContentHashAndNoDocument() {
    TableEntity te = base();
    te.addProperty("contentHash", "abc123");

    ReceiptEntity re = ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(te);

    assertThat(re.getDocument()).isNull();
    assertThat(re.getContentHash()).isEqualTo("abc123");
    assertThat(re.getOrganizationFiscalCode()).isEqualTo("CF1");
    assertThat(re.getIuv()).isEqualTo("IUV1");
  }

  @Test
  void entityWithoutDocumentNorContentHashMapsWithoutError() {
    ReceiptEntity re = ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(base());

    assertThat(re.getDocument()).isNull();
    assertThat(re.getContentHash()).isNull();
  }
}
