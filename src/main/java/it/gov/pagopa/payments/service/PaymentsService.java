package it.gov.pagopa.payments.service;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import feign.FeignException;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.entity.Status;
import it.gov.pagopa.payments.exception.AppError;
import it.gov.pagopa.payments.exception.AppException;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntity;
import it.gov.pagopa.payments.model.PaymentOptionStatus;
import it.gov.pagopa.payments.model.PaymentsModelResponse;
import it.gov.pagopa.payments.model.PaymentsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PaymentsService {
    private static final String PARTITION_KEY_FIELD = "PartitionKey";
    private static final String ROW_KEY_FIELD = "RowKey";
    private static final String DEBTOR_FIELD = "Debtor";
    private static final String STATUS_FIELD = "Status";
    private static final String[] columns =
            new String[] {PARTITION_KEY_FIELD, ROW_KEY_FIELD, DEBTOR_FIELD, STATUS_FIELD};

    public final static String STATUS_PROPERTY = "status";

    @Autowired private TableClient tableClient;
    @Autowired
    private GpdClient gpdClient;

    public PaymentsService() {}

    public PaymentsService(GpdClient gpdClient, TableClient tableClient) {
        this.gpdClient = gpdClient;
        this.tableClient = tableClient;
    }

    public PaymentsResult<ReceiptEntity> getOrganizationReceipts(
            @NotBlank String organizationFiscalCode,
            String debtor,
            String service,
            String from,
            String to) {
        try {

            List<ReceiptEntity> filteredEntities = retrieveEntitiesByFilter(tableClient,
                    organizationFiscalCode, debtor, service, from, to);
            return this.setReceiptsOutput(getGPDCheckedReceiptsList(filteredEntities, tableClient));

        }
        catch (TableServiceException e) {
            log.error("Error in processing get organizations list", e);
            throw new AppException(AppError.DB_ERROR, "ALL");
        }
    }

    public ReceiptEntity getReceiptByOrganizationFCAndIUV(
            @Validated @NotBlank String organizationFiscalCode, @Validated @NotBlank String iuv) {

        final String LOG_BASE_PARAMS_DETAIL = "organizationFiscalCode= %s, iuv=%s";
        try{
            TableEntity tableEntity = tableClient.getEntity(organizationFiscalCode, iuv);
            this.checkGPDDebtPosStatus(tableEntity, tableClient);
            return ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(tableEntity);
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

    public List<ReceiptEntity> getGPDCheckedReceiptsList(List<ReceiptEntity> result, TableClient tableClient) {
        // for all the receipts in the azure table, only those that have been already PAID status or are
        // in PAID status on GPD are returned
        List<ReceiptEntity> checkedReceipts = new ArrayList<>();
        for (ReceiptEntity re : result) {
            try {
                this.checkGPDDebtPosStatus(new TableEntity(re.getOrganizationFiscalCode(), re.getIuv()), tableClient);
                checkedReceipts.add(re);
            } catch (FeignException.NotFound e) {
                log.error(
                        "[getGPDCheckedReceiptsList] Non-blocking error: "
                                + "get not found exception in the recovery of payment options",
                        e);
            } catch (AppException e) {
                log.error(
                        "[getGPDCheckedReceiptsList] Non-blocking error: Receipt is not in an eligible state on"
                                + " GPD in order to be returned to the caller",
                        e);
            }
        }
        return checkedReceipts;
    }

    public List<ReceiptEntity> retrieveEntitiesByFilter(TableClient tableClient, String organizationFiscalCode,
                                                        String debtor, String service, String from, String to) {

        List<String> filters = new ArrayList<>();

        filters.add(String.format("PartitionKey eq '%s'", organizationFiscalCode));

        if(null != debtor){
            filters.add(String.format("debtor eq '%s'", debtor));
        }

        if(null != service){
            PaymentsService.getStartsWithFilter(filters, service);
        }

        if(null != from && null != to){
            filters.add(String.format("paymentDate ge '%s' and paymentDate le '%s'", from, to));
        }

        List<ReceiptEntity> modelList = tableClient.listEntities(new ListEntitiesOptions()
                        .setFilter(String.join(" and ", filters)), null, null)
                        .stream()
                        .map(ConvertTableEntityToReceiptEntity::mapTableEntityToReceiptEntity)
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

    private PaymentsResult<ReceiptEntity> setReceiptsOutput(List<ReceiptEntity> listOfEntity) {
        PaymentsResult<ReceiptEntity> result = new PaymentsResult<>();
        result.setResults(listOfEntity);
        result.setLength(listOfEntity.size());
        return result;
    }
}
