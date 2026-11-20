
package it.gov.pagopa.backfill;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.payments.service.ReceiptBlobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "receipts.backfill.seed.enabled",
    havingValue = "true"
)
public class ReceiptBackfillTestSeeder {

  @Value("${receipts.backfill.seed.dry-run:true}")
  private boolean dryRun;

  @Value("${receipts.backfill.seed.delay-ms:50}")
  private long delayMs;

  private final TableClient tableClient;
  private final ReceiptBlobStore receiptBlobStore;

  private static final long NOTICE_NUMBER_START =
      347414787621970000L;

  private static final Map<String, Integer> RECEIPTS_PER_CF =
      createReceiptsPerCf();

  private static Map<String, Integer> createReceiptsPerCf() {
    Map<String, Integer> result = new LinkedHashMap<>();
    result.put("77777777777", 1000);
    result.put("00112233445", 500);
    result.put("88888888888", 200);
    return result;
  }

  private enum SeedScenario {
    DOCUMENT_ONLY,
    ALREADY_MIGRATED,
    DOCUMENT_WITH_VALID_HASH,
    DOCUMENT_WITH_INVALID_HASH,
    INCONSISTENT,
    HASH_WITHOUT_BLOB
  }

  public void seed() {

    log.info("Starting receipts test seeding");

    long noticeNumberCounter = NOTICE_NUMBER_START;
    int totalCreated = 0;

    for (Map.Entry<String, Integer> entry :
        RECEIPTS_PER_CF.entrySet()) {

      String cf = entry.getKey();
      int count = entry.getValue();

      log.info("Seeding CF={}, receipts={}", cf, count);

      Map<SeedScenario, Integer> scenarioCounts =
          new LinkedHashMap<>();

      for (SeedScenario scenario : SeedScenario.values()) {
        scenarioCounts.put(scenario, 0);
      }

      for (int i = 0; i < count; i++) {

        String noticeNumber =
            String.valueOf(noticeNumberCounter++);

        String creditorReferenceId =
            noticeNumber.substring(3);

        String iuv = creditorReferenceId;

        String receiptId = UUID.randomUUID()
            .toString()
            .replace("-", "");

        String xml = createReceiptXml(
            cf,
            receiptId,
            noticeNumber,
            creditorReferenceId
        );

        SeedScenario scenario = getScenario(i, count);

        TableEntity entity = new TableEntity(cf, iuv);

        byte[] document =
            xml.getBytes(StandardCharsets.UTF_8);

        String hash = sha256(document);

        switch (scenario) {

          case DOCUMENT_ONLY:
            entity.addProperty("document", xml);
            break;

          case ALREADY_MIGRATED:
            // Simula una ricevuta già migrata.
            // Prima crea il Blob, poi la entity Table.
            if (!dryRun) {
              receiptBlobStore.upload(cf, iuv, document);
            }
            entity.addProperty("contentHash", hash);
            break;

          case DOCUMENT_WITH_VALID_HASH:
            entity.addProperty("document", xml);
            entity.addProperty("contentHash", hash);
            break;

          case DOCUMENT_WITH_INVALID_HASH:
            entity.addProperty("document", xml);
            entity.addProperty(
                "contentHash",
                "INVALID_HASH"
            );
            break;

          case INCONSISTENT:
            // Nessun document e nessun contentHash.
            break;

          case HASH_WITHOUT_BLOB:
            // Simula una ricevuta dichiarata migrata
            // ma senza payload sul Blob Storage.
            entity.addProperty("contentHash", hash);
            break;
        }

        try {
          if (!dryRun) {
            tableClient.createEntity(entity);
          }

          if (delayMs > 0) {
            try {
              Thread.sleep(delayMs);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(
                  "Seeder interrupted", e
              );
            }
          }

          scenarioCounts.merge(
              scenario,
              1,
              Integer::sum
          );

          totalCreated++;

          log.debug(
              "Created receipt. CF={}, IUV={}, scenario={}",
              cf,
              iuv,
              scenario
          );

        } catch (TableServiceException e) {

          log.error(
              "Failed to create receipt. CF={}, IUV={}, scenario={}",
              cf,
              iuv,
              scenario,
              e
          );

          throw e;
        }
      }

      log.info(
          "Completed seeding CF={}, expectedScenarios={}",
          cf,
          scenarioCounts
      );
    }

    log.info(
        "Receipt test seeding completed. totalCreated={}",
        totalCreated
    );
  }

  private SeedScenario getScenario(int index, int total) {

    int percentage = index * 100 / total;

    if (percentage < 40) {
      return SeedScenario.DOCUMENT_ONLY;
    }

    if (percentage < 60) {
      return SeedScenario.ALREADY_MIGRATED;
    }

    if (percentage < 70) {
      return SeedScenario.DOCUMENT_WITH_VALID_HASH;
    }

    if (percentage < 80) {
      return SeedScenario.DOCUMENT_WITH_INVALID_HASH;
    }

    if (percentage < 90) {
      return SeedScenario.INCONSISTENT;
    }

    return SeedScenario.HASH_WITHOUT_BLOB;
  }

  private String createReceiptXml(
      String cf,
      String receiptId,
      String noticeNumber,
      String creditorReferenceId) {

    return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <paSendRTReq>
                    <idPA>%s</idPA>
                    <idBrokerPA>15376371009</idBrokerPA>
                    <idStation>15376371009_01</idStation>
                    <receipt>
                        <receiptId>%s</receiptId>
                        <noticeNumber>%s</noticeNumber>
                        <fiscalCode>%s</fiscalCode>
                        <outcome>OK</outcome>
                        <creditorReferenceId>%s</creditorReferenceId>
                        <paymentAmount>10.00</paymentAmount>
                        <description>Pagamento compenso spettacolo "Tel chi el telun"</description>
                        <companyName>SkyLab Inc.</companyName>
                        <officeName>SkyLab - Sede via Washington</officeName>
                        <debtor>
                            <uniqueIdentifier>
                                <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                <entityUniqueIdentifierValue>VNTMHL76M09H501D</entityUniqueIdentifierValue>
                            </uniqueIdentifier>
                            <fullName>Michele Ventimiglia</fullName>
                            <streetName>via Washington</streetName>
                            <civicNumber>11</civicNumber>
                            <postalCode>89812</postalCode>
                            <city>Pizzo Calabro</city>
                            <stateProvinceRegion>VV</stateProvinceRegion>
                            <country>IT</country>
                            <e-mail>micheleventimiglia@skilabmail.com</e-mail>
                        </debtor>
                        <transferList>
                            <transfer>
                                <idTransfer>1</idTransfer>
                                <transferAmount>1.00</transferAmount>
                                <fiscalCodePA>%s</fiscalCodePA>
                                <IBAN>IT30N0103076271000001823603</IBAN>
                                <remittanceInformation>Rata 1</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                            <transfer>
                                <idTransfer>2</idTransfer>
                                <transferAmount>2.00</transferAmount>
                                <fiscalCodePA>%s</fiscalCodePA>
                                <IBAN>IT30N0103076271000001823603</IBAN>
                                <remittanceInformation>Rata 2</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                            <transfer>
                                <idTransfer>3</idTransfer>
                                <transferAmount>2.00</transferAmount>
                                <fiscalCodePA>%s</fiscalCodePA>
                                <IBAN>IT30N0103076271000001823603</IBAN>
                                <remittanceInformation>Rata 3</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                            <transfer>
                                <idTransfer>4</idTransfer>
                                <transferAmount>2.00</transferAmount>
                                <fiscalCodePA>%s</fiscalCodePA>
                                <IBAN>IT30N0103076271000001823603</IBAN>
                                <remittanceInformation>Rata 4</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                            <transfer>
                                <idTransfer>5</idTransfer>
                                <transferAmount>3.00</transferAmount>
                                <fiscalCodePA>%s</fiscalCodePA>
                                <IBAN>IT30N0103076271000001823603</IBAN>
                                <remittanceInformation>Rata 5</remittanceInformation>
                                <transferCategory>G</transferCategory>
                            </transfer>
                        </transferList>
                        <idPSP>AGID_02</idPSP>
                        <pspFiscalCode>undefined</pspFiscalCode>
                        <PSPCompanyName>PSP_Agid</PSPCompanyName>
                        <idChannel>97735020584_05</idChannel>
                        <channelDescription>app</channelDescription>
                        <payer>
                            <uniqueIdentifier>
                                <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
                                <entityUniqueIdentifierValue>VNTMHL76M09H501D</entityUniqueIdentifierValue>
                            </uniqueIdentifier>
                            <fullName>Michele Ventimiglia</fullName>
                            <streetName>via Washington</streetName>
                            <civicNumber>11</civicNumber>
                            <postalCode>89812</postalCode>
                            <city>Pizzo Calabro</city>
                            <stateProvinceRegion>VV</stateProvinceRegion>
                            <country>IT</country>
                            <e-mail>micheleventimiglia@skilabmail.com</e-mail>
                        </payer>
                        <paymentMethod>creditCard</paymentMethod>
                        <fee>1.50</fee>
                        <paymentDateTime>2023-08-28T12:00:00</paymentDateTime>
                        <applicationDate>2023-08-28</applicationDate>
                        <transferDate>2023-08-29</transferDate>
                    </receipt>
                </paSendRTReq>
                """.formatted(
        cf,
        receiptId,
        noticeNumber,
        cf,
        creditorReferenceId,
        cf,
        cf,
        cf,
        cf,
        cf
    );
  }

  private String sha256(byte[] value) {
    try {
      byte[] hash = MessageDigest
          .getInstance("SHA-256")
          .digest(value);

      return HexFormat.of().formatHex(hash);

    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(
          "SHA-256 not available",
          e
      );
    }
  }
}
