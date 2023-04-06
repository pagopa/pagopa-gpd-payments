package it.gov.pagopa.payments.service;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.table.CloudTable;
import com.microsoft.azure.storage.table.TableOperation;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.entity.ReceiptEntityCosmos;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.PaymentsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class PaymentsServiceCosmos{


    private static final String PARTITION_KEY_FIELD = "PartitionKey";
    private static final String ROW_KEY_FIELD = "RowKey";
    private static final String DEBTOR_FIELD = "Debtor";
    private static final String STATUS_FIELD = "Status";
    private static final String[] columns =
            new String[] {PARTITION_KEY_FIELD, ROW_KEY_FIELD, DEBTOR_FIELD, STATUS_FIELD};

    public final static String DEBTOR_PROPERTY = "debtor";
    public final static String DOCUMENT_PROPERTY = "document";
    public final static String STATUS_PROPERTY = "status";

    @Value("${azure.tables.connection.string}")
    private String tableConnectionString;

    @Value("${receipts.table}")
    private String receiptsTable;

    @Autowired
    private GpdClient gpdClient;

    public PaymentsServiceCosmos() {}

    public PaymentsServiceCosmos(
            String tableConnectionString, String receiptsTable, GpdClient gpdClient) {
        this.tableConnectionString = tableConnectionString;
        this.receiptsTable = receiptsTable;
        this.gpdClient = gpdClient;
    }

    public ReceiptEntityCosmos getReceiptByOrganizationFCAndIUV(
            @Validated @NotBlank String organizationFiscalCode, @Validated @NotBlank String iuv) {

        final String LOG_BASE_PARAMS_DETAIL = "organizationFiscalCode= %s, iuv=%s";
        try{
            TableEntity tableEntity = getOrganizationTable().getEntity(organizationFiscalCode, iuv);
            this.checkGPDDebtPosStatus(tableEntity, getOrganizationTable());
            return new ReceiptEntityCosmos(tableEntity.getPartitionKey(), tableEntity.getRowKey(),
                tableEntity.getProperty(DEBTOR_PROPERTY).toString(), tableEntity.getProperty(DOCUMENT_PROPERTY).toString(),
                tableEntity.getProperty(STATUS_PROPERTY).toString());
        } catch (TableServiceException e) {
            log.error("Error in organization table connection", e);
            throw new AppException(AppError.NOT_CONNECTED);
        }
    }

    public void checkGPDDebtPosStatus(TableEntity receipt, TableClient tableClient) {
        TableEntity tableEntity = tableClient.getEntity(receipt.getPartitionKey(), receipt.getRowKey());
        // the check on GPD is necessary if the status of the receipt is different from PAID
        if (!tableEntity.getProperty(STATUS_PROPERTY).toString().trim().equalsIgnoreCase(Status.PAID.name())) {
            PaymentsModelResponse paymentOption =
                    gpdClient.getPaymentOption(tableEntity.getPartitionKey(), tableEntity.getRowKey());
            if (null != paymentOption && !PaymentOptionStatus.PO_PAID.equals(paymentOption.getStatus())) {
                throw new AppException(
                        AppError.UNPROCESSABLE_RECEIPT,
                        paymentOption.getStatus(),
                        tableEntity.getPartitionKey(),
                        tableEntity.getRowKey());
            }
            // if no exception is raised the status on GPD is correctly in PAID -> for congruence update
            // receipt status
            tableEntity.addProperty(STATUS_PROPERTY, Status.PAID.name());
            tableClient.updateEntity(tableEntity);
        }
    }

    private TableClient getOrganizationTable() {
        try {
            TableServiceClient tableServiceClient = new TableServiceClientBuilder()
                    .connectionString(tableConnectionString)
                    .buildClient();
            return tableServiceClient.getTableClient(receiptsTable);
        } catch (TableServiceException e) {
            log.error("Error in organization table connection", e);
            throw new AppException(AppError.NOT_CONNECTED);
        }
    }
}
