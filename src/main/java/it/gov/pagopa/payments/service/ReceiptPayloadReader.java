package it.gov.pagopa.payments.service;

import io.micrometer.core.instrument.MeterRegistry;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.exception.ReceiptHashMismatchException;
import it.gov.pagopa.payments.exception.ReceiptInconsistentException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReceiptPayloadReader {

  private final ReceiptBlobStore blobStore;
  private final boolean verifyHash;
  private final MeterRegistry metrics;

  public ReceiptPayloadReader(
      ReceiptBlobStore blobStore,
      @Value("${receipts.blob.verify-hash:true}") boolean verifyHash,
      MeterRegistry metrics) {
    this.blobStore = blobStore;
    this.verifyHash = verifyHash;
    this.metrics = metrics;
  }

  public String readDocument(ReceiptEntity entity) {
    String cf = entity.getOrganizationFiscalCode();
    String iuv = entity.getIuv();
    String contentHash = entity.getContentHash(); // null per entity legacy

    if (contentHash != null && !contentHash.isBlank()) {
      return readFromBlob(cf, iuv, contentHash);
    }
    return readFromEntity(entity, cf, iuv);
  }

  private String readFromBlob(String cf, String iuv, String expectedHash) {
    byte[] data = blobStore.download(cf, iuv); // 404 -> ReceiptBlobNotFoundException
    if (verifyHash) {
      String actual = sha256Hex(data);
      if (!actual.equalsIgnoreCase(expectedHash)) {
        metrics.counter("receipt.blob.hash_mismatch").increment();
        throw new ReceiptHashMismatchException(cf, iuv);
      }
    }
    metrics.counter("receipt.read", "source", "blob").increment();
    return new String(data, StandardCharsets.UTF_8);
  }

  private String readFromEntity(ReceiptEntity entity, String cf, String iuv) {
    String document = entity.getDocument();
    if (document == null) {
      metrics.counter("receipt.read.inconsistent").increment();
      throw new ReceiptInconsistentException(cf, iuv);
    }
    metrics.counter("receipt.read", "source", "entity").increment();
    return document;
  }

  static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
