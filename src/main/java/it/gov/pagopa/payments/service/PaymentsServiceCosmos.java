package it.gov.pagopa.payments.service;

import com.azure.core.http.rest.PagedFlux;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.Context;
import com.azure.core.util.paging.ContinuablePagedIterable;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.implementation.EntityPaged;
import com.azure.data.tables.implementation.TableUtils;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import it.gov.pagopa.payments.entity.ReceiptEntityCosmos;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntityCosmos;
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
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PaymentsServiceCosmos{

    private static final String PARTITION_KEY_FIELD = "PartitionKey";
    private static final String ROW_KEY_FIELD = "RowKey";
    private static final String DEBTOR_FIELD = "Debtor";
    private static final String STATUS_FIELD = "Status";
    private static final String[] columns =
            new String[] {PARTITION_KEY_FIELD, ROW_KEY_FIELD, DEBTOR_FIELD, STATUS_FIELD};

    public final static String STATUS_PROPERTY = "status";

    @Value("${azure.tables.connection.string}")
    private String tableConnectionString;

    @Value("${azure.tables.tableName}")
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

    public PaymentsResult<ReceiptEntityCosmos> getOrganizationReceipts(
            @NotBlank String organizationFiscalCode,
            String debtor,
            String service,
            String from,
            String to) {
        List<ReceiptEntityCosmos> receiptList = new ArrayList<>();

        try {

            List<ReceiptEntityCosmos> filteredEntities = retrieveEntitiesByFilter(getOrganizationTable(),
                    organizationFiscalCode, debtor, service, from, to);
            return this.setReceiptsOutput(filteredEntities);

        }
        catch (TableServiceException e) {
            log.error("Error in processing get organizations list", e);
            throw new AppException(AppError.DB_ERROR, "ALL");
        }
    }

    public ReceiptEntityCosmos getReceiptByOrganizationFCAndIUV(
            @Validated @NotBlank String organizationFiscalCode, @Validated @NotBlank String iuv) {

        final String LOG_BASE_PARAMS_DETAIL = "organizationFiscalCode= %s, iuv=%s";
        try{
            TableEntity tableEntity = getOrganizationTable().getEntity(organizationFiscalCode, iuv);
            this.checkGPDDebtPosStatus(tableEntity, getOrganizationTable());
            return ConvertTableEntityToReceiptEntityCosmos.mapTableEntityToReceiptEntity(tableEntity);
        } catch (TableServiceException e) {
            log.error("Error in organization table connection", e);
            throw new AppException(AppError.DB_ERROR);
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

    public List<ReceiptEntityCosmos> retrieveEntitiesByFilter(TableClient tableClient, String organizationFiscalCode,
                                                              String debtor, String service, String from, String to) {

        List<String> filters = new ArrayList<>();

        filters.add(String.format("PartitionKey eq '%s'", organizationFiscalCode));

        if(null != debtor){
            filters.add(String.format("debtor eq '%s'", debtor));
        }

        if(null != service){
            PaymentsServiceCosmos.getStartsWithFilter(filters, service);
        }

        if(null != from && null != to){
            filters.add(String.format("paymentDate ge '%s' and paymentDate le '%s'", from, to));
        }

        List<ReceiptEntityCosmos> modelList = tableClient.listEntities(new ListEntitiesOptions()
                        .setFilter(String.join(" and ", filters)), null, null)
                        .stream()
                        .map(ConvertTableEntityToReceiptEntityCosmos::mapTableEntityToReceiptEntity)
                        .collect(Collectors.toList());
        return modelList;
    }

    private static void getStartsWithFilter(List<String> filters, String startsWith) {
        var length = startsWith.length() - 1;
        var nextChar = startsWith.toCharArray()[length] + 1;

        var startWithEnd = startsWith.substring(0, length) + (char) nextChar;

        filters.add(String.format("Rowkey ge %s", startsWith));
        filters.add(String.format("Rowkey lt %s", startWithEnd));
    }

    private PaymentsResult<ReceiptEntityCosmos> setReceiptsOutput(List<ReceiptEntityCosmos> listOfEntity) {
        PaymentsResult<ReceiptEntityCosmos> result = new PaymentsResult<>();
        result.setResults(listOfEntity);
        result.setLength(listOfEntity.size());
        return result;
    }

    private TableClient getOrganizationTable() {
        try {
            TableServiceClient tableServiceClient = new TableServiceClientBuilder()
                    .connectionString(tableConnectionString)
                    .buildClient();
            return tableServiceClient.getTableClient(receiptsTable);
        } catch (TableServiceException e) {
            log.error("Error in organization table connection", e);
            throw new AppException(AppError.DB_ERROR);
        }
    }
}
