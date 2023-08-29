package it.gov.pagopa.payments.service;

import com.azure.core.http.rest.PagedResponse;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
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

import javax.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class PaymentsService {

    public static final String STATUS_PROPERTY = "status";

    @Autowired private TableClient tableClient;
    @Autowired
    private GpdClient gpdClient;

    public PaymentsService(GpdClient gpdClient, TableClient tableClient) {
        this.gpdClient = gpdClient;
        this.tableClient = tableClient;
    }

    public PaymentsResult<ReceiptEntity> getOrganizationReceipts(
            @NotBlank String organizationFiscalCode,
            String debtor,
            String service,
            String from,
            String to,
            int pageNum,
            int pageSize) {
        try {

            List<ReceiptEntity> filteredEntities = retrieveEntitiesByFilter(tableClient,
                    organizationFiscalCode, debtor, service, from, to, pageNum, pageSize);
            return this.setReceiptsOutput(getGPDCheckedReceiptsList(filteredEntities, tableClient));

        }
        catch (TableServiceException e) {
            log.error("Error in processing get organizations list", e);
            throw new AppException(AppError.DB_ERROR, "ALL");
        }
    }

    public ReceiptEntity getReceiptByOrganizationFCAndIUV(
            @NotBlank String organizationFiscalCode, @NotBlank String iuv, ArrayList<String> segregationCodes) {

        try {
            if(segregationCodes != null)
                isBrokerAuthorized(iuv, segregationCodes);
            TableEntity tableEntity = tableClient.getEntity(organizationFiscalCode, iuv);
            this.checkGPDDebtPosStatus(tableEntity, tableClient);
            return ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(tableEntity);
        } catch (TableServiceException e) {
            if(e.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND){
                throw new AppException(AppError.RECEIPT_NOT_FOUND, organizationFiscalCode, iuv);
            }
            log.error("Error in organization table connection", e);
            throw new AppException(AppError.DB_ERROR);
        }
    }

    private boolean isBrokerAuthorized(String iuv, ArrayList<String> brokerSegregationCodes) {
        // verify that IUV is linked with one of segregation code for which the broker is authorized
        String iuvSegregationCode = iuv.substring(1,3);

        for(String segCode: brokerSegregationCodes) {
            if(segCode.equals(iuvSegregationCode))
                return true;
        }

        return false;
    }

    public void checkGPDDebtPosStatus(TableEntity receipt, TableClient tableClient) {
        TableEntity tableEntity = tableClient.getEntity(receipt.getPartitionKey(), receipt.getRowKey());
        // the check on GPD is necessary if the status of the receipt is different from PAID
        try{
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
        } catch (TableServiceException e){
            throw new AppException(AppError.DB_ERROR, "Error when updating receipt status");
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
                                                        String debtor, String service, String from,
                                                        String to, int pageNum, int pageSize) {

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

        Iterator<PagedResponse<TableEntity>> filteredIterator = tableClient
                .listEntities(
                        new ListEntitiesOptions()
                                .setTop(pageSize)
                                .setFilter(String.join(" and ", filters)
                                ), null, null)
                .iterableByPage()
                .iterator();

        for(int i = 0; i < pageNum - 1; i++){
            if(!filteredIterator.hasNext()){
                throw new AppException(AppError.NOT_ENOUGH_ELEMENTS);
            } else {
                filteredIterator.next();
            }
        }

        if(!filteredIterator.hasNext()) {
            throw new AppException(AppError.NOT_ENOUGH_ELEMENTS);
        }

        return filteredIterator.next().getValue().stream().map(ConvertTableEntityToReceiptEntity::mapTableEntityToReceiptEntity).collect(Collectors.toList());
    }

    private static void getStartsWithFilter(List<String> filters, String startsWith) {
        var length = startsWith.length() - 1;
        var nextChar = startsWith.toCharArray()[length] + 1;

        var startWithEnd = startsWith.substring(0, length) + (char) nextChar;

        filters.add(String.format("RowKey ge '%s'", startsWith));
        filters.add(String.format("RowKey lt '%s'", startWithEnd));
    }

    private PaymentsResult<ReceiptEntity> setReceiptsOutput(List<ReceiptEntity> listOfEntity) {
        PaymentsResult<ReceiptEntity> result = new PaymentsResult<>();
        result.setResults(listOfEntity);
        result.setLength(listOfEntity.size());
        return result;
    }
}
