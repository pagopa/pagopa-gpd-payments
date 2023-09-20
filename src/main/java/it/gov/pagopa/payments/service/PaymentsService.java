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
            int pageSize,
            ArrayList<String> segCodes) {
        try {
            List<ReceiptEntity> filteredEntities = retrieveEntitiesByFilter(tableClient,
                    organizationFiscalCode, debtor, service, from, to, pageNum, pageSize, segCodes);
            return this.setReceiptsOutput(getGPDCheckedReceiptsList(filteredEntities, tableClient));

        }
        catch (TableServiceException e) {
            log.error("Error in processing get organizations list", e);
            throw new AppException(AppError.DB_ERROR, "ALL");
        }
    }

    public ReceiptEntity getReceiptByOrganizationFCAndIUV(
            @NotBlank String organizationFiscalCode, @NotBlank String iuv, ArrayList<String> validSegregationCodes) {

        try {
            if(validSegregationCodes != null && iuv.length() > 3) {
                String iuvSegregationCode = iuv.substring(1,3);
                if(!isBrokerAuthorized(iuvSegregationCode, validSegregationCodes))
                    throw new AppException(AppError.FORBIDDEN_SEGREGATION_CODE, iuvSegregationCode, organizationFiscalCode, iuv);
            }

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

    private boolean isBrokerAuthorized(String iuvSegregationCode, ArrayList<String> brokerSegregationCodes) {
        // verify that IUV is linked with one of segregation code for which the broker is authorized
        for(String code: brokerSegregationCodes) {
            if(code.equals(iuvSegregationCode))
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
                if (paymentOption != null && paymentOption.getStatus().equals(PaymentOptionStatus.PO_UNPAID)) {
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
                                                        String to, int pageNum, int pageSize, ArrayList<String> segCodes) {

        List<String> filters = new ArrayList<>();

        filters.add(String.format("PartitionKey eq '%s'", organizationFiscalCode));

        if(null != debtor){
            filters.add(String.format("debtor eq '%s'", debtor));
        }

        if(null != service){
            filters.add(this.getStartsWithFilter("RowKey", service));
        }

        if(null != from && null != to){
            filters.add(String.format("paymentDate ge '%s' and paymentDate le '%s'", from, to));
        }

        String filter = String.join(" and ", filters);

        if(segCodes != null && !segCodes.isEmpty()) {
            ArrayList<String> segCodesFilters = new ArrayList<>();
            for(String segCode: segCodes) {
                segCodesFilters.add(this.getStartsWithFilter("RowKey", "3" + segCode) + " and " + filter);
            }
            filter = String.join(" or ", segCodesFilters);
        }

        Iterator<PagedResponse<TableEntity>> filteredIterator = tableClient
                .listEntities(
                        new ListEntitiesOptions()
                                .setTop(pageSize)
                                .setFilter(filter),
                        null,
                        null)
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

    private static String getStartsWithFilter(String field, String startsWith) {
        int length = startsWith.length() - 1;
        int nextChar = startsWith.toCharArray()[length] + 1;

        String startWithEnd = startsWith.substring(0, length) + (char) nextChar;

        return String.format("%s ge '%s' and %s lt '%s'", field, startsWith, field, startWithEnd);
    }

    private PaymentsResult<ReceiptEntity> setReceiptsOutput(List<ReceiptEntity> listOfEntity) {
        PaymentsResult<ReceiptEntity> result = new PaymentsResult<>();
        result.setResults(listOfEntity);
        result.setLength(listOfEntity.size());
        return result;
    }
}
