package it.gov.pagopa.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.ReceiptBlobNotFoundException;
import it.gov.pagopa.payments.exception.ReceiptHashMismatchException;
import it.gov.pagopa.payments.exception.ReceiptInconsistentException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiptPayloadReaderTest {

  private static final String CF = "CF123";
  private static final String IUV = "IUV456";
  private static final String CONTENT = "<xml>receipt</xml>";

  private ReceiptBlobStore blobStore;
  private ReceiptPayloadReader reader;

  @BeforeEach
  void setUp() {
    blobStore = mock(ReceiptBlobStore.class);
    reader = new ReceiptPayloadReader(blobStore, true, new SimpleMeterRegistry());
  }

  private ReceiptEntity entity(String document, String contentHash) {
    ReceiptEntity e = new ReceiptEntity(CF, IUV);
    e.setDocument(document);
    e.setContentHash(contentHash);
    return e;
  }

  @Test
  void legacyEntityReturnsDocumentWithoutTouchingBlob() {
    assertThat(reader.readDocument(entity(CONTENT, null))).isEqualTo(CONTENT);
    verifyNoInteractions(blobStore);
  }

  @Test
  void blankContentHashIsTreatedAsLegacy() {
    assertThat(reader.readDocument(entity(CONTENT, " "))).isEqualTo(CONTENT);
    verifyNoInteractions(blobStore);
  }

  @Test
  void entityWithContentHashReadsFromBlob() {
    byte[] data = CONTENT.getBytes(StandardCharsets.UTF_8);
    when(blobStore.download(CF, IUV)).thenReturn(data);

    String hash = ReceiptPayloadReader.sha256Hex(data);
    assertThat(reader.readDocument(entity(null, hash))).isEqualTo(CONTENT);
  }

  @Test
  void hashComparisonIsCaseInsensitive() {
    byte[] data = CONTENT.getBytes(StandardCharsets.UTF_8);
    when(blobStore.download(CF, IUV)).thenReturn(data);

    String hash = ReceiptPayloadReader.sha256Hex(data).toUpperCase();
    assertThat(reader.readDocument(entity(null, hash))).isEqualTo(CONTENT);
  }

  @Test
  void hashMismatchThrows() {
    when(blobStore.download(CF, IUV)).thenReturn(CONTENT.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> reader.readDocument(entity(null, "deadbeef")))
        .isInstanceOf(ReceiptHashMismatchException.class);
  }

  @Test
  void hashMismatchIsIgnoredWhenVerificationDisabled() {
    reader = new ReceiptPayloadReader(blobStore, false, new SimpleMeterRegistry());
    when(blobStore.download(CF, IUV)).thenReturn(CONTENT.getBytes(StandardCharsets.UTF_8));

    assertThat(reader.readDocument(entity(null, "deadbeef"))).isEqualTo(CONTENT);
  }

  @Test
  void missingBlobIsPropagatedWithoutLegacyFallback() {
    when(blobStore.download(CF, IUV))
        .thenThrow(new ReceiptBlobNotFoundException(CF, IUV, new RuntimeException()));

    // anche se l'entity avesse ancora il document, con contentHash il blob è la fonte
    assertThatThrownBy(() -> reader.readDocument(entity(CONTENT, "abc")))
        .isInstanceOf(ReceiptBlobNotFoundException.class);
  }

  @Test
  void neitherContentHashNorDocumentThrows() {
    assertThatThrownBy(() -> reader.readDocument(entity(null, null)))
        .isInstanceOf(ReceiptInconsistentException.class);
  }
}
