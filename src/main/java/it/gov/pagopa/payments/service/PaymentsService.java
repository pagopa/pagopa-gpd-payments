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
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptModelResponse;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.client.GpdClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotBlank;
import java.util.*;

@Service
@Slf4j
public class PaymentsService {

    public static final String STATUS_PROPERTY = "status";

    public static final String ROWKEY_PROPERTY = "RowKey";

    public static final String DEBTOR_PROPERTY = "debtor";

    @Autowired private TableClient tableClient;
    @Autowired
    private GpdClient gpdClient;

    public PaymentsService(GpdClient gpdClient, TableClient tableClient) {
        this.gpdClient = gpdClient;
        this.tableClient = tableClient;
    }

    public PaymentsResult<ReceiptModelResponse> getOrganizationReceipts(
            @NotBlank String organizationFiscalCode,
            String debtor,
            String service,
            String from,
            String to,
            int pageNum,
            int pageSize,
            List<String> segCodes,
            String debtorOrIuv) {
        try {
            PageInfo filteredEntities = retrieveEntitiesByFilter(tableClient,
                    organizationFiscalCode, debtor, service, from, to, pageNum, pageSize, segCodes, debtorOrIuv);
            return this.setReceiptsOutput(getGPDCheckedReceiptsList(filteredEntities.getReceiptsList(), tableClient),
                    filteredEntities.getTotalPages(), pageNum);

        } catch (TableServiceException e) {
            log.error("Error in processing get organizations list", e);
            throw new AppException(AppError.DB_ERROR, "ALL");
        }
    }

    public ReceiptEntity getReceiptByOrganizationFCAndIUV(
            @NotBlank String organizationFiscalCode, @NotBlank String iuv, List<String> validSegregationCodes) {

        try {
            if(validSegregationCodes != null && !validSegregationCodes.isEmpty() && iuv.length() > 1) {
                String iuvSegregationCode = iuv.substring(0,2);
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

    private boolean isBrokerAuthorized(String iuvSegregationCode, List<String> brokerSegregationCodes) {
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

    public List<ReceiptModelResponse> getGPDCheckedReceiptsList(List<ReceiptModelResponse> result, TableClient tableClient) {
        // for all the receipts in the azure table, only those that have been already PAID status or are
        // in PAID status on GPD are returned
        List<ReceiptModelResponse> checkedReceipts = new ArrayList<>();
        for (ReceiptModelResponse re : result) {
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

    public PageInfo retrieveEntitiesByFilter(TableClient tableClient, String organizationFiscalCode,
                                             String debtor, String service, String from,
                                             String to, int pageNum, int pageSize,
                                             List<String> segCodes, String debtorOrIuv) {

        List<String> filters = new ArrayList<>();

        filters.add(String.format("PartitionKey eq '%s'", organizationFiscalCode));

        if(null != debtor){
            filters.add(String.format("debtor eq '%s'", debtor));
        }

        if(null != service){
            filters.add(getStartsWithFilter(ROWKEY_PROPERTY, service));
        }

        if(null != from && null != to){
            filters.add(String.format("paymentDate ge '%s' and paymentDate le '%s'", from, to));
        }

        if(debtorOrIuv != null) {
            String iuvFilter = getStartsWithFilter(ROWKEY_PROPERTY, debtorOrIuv);
            String debtorFilter = getStartsWithFilter(DEBTOR_PROPERTY, debtorOrIuv);
            filters.add('(' + String.join(" or ", '(' + iuvFilter + ')', '(' + debtorFilter + ')') + ')');
        }

        String filter = String.join(" and ", filters);

        if(segCodes != null && !segCodes.isEmpty()) {
            ArrayList<String> segCodesFilters = new ArrayList<>();
            for(String segCode: segCodes) {
                segCodesFilters.add(getStartsWithFilter(ROWKEY_PROPERTY, segCode) + " and " + filter);
            }
            filter = String.join(" or ", segCodesFilters);
        }

        Iterator<PagedResponse<TableEntity>> filteredReceiptIterator = tableClient
                .listEntities(
                        new ListEntitiesOptions()
                                .setFilter(filter)
                                .setTop(pageSize),
                        null,
                        null)
                .iterableByPage()
                .iterator();

        int totalPages = 0;
        List<ReceiptModelResponse> filteredReceipts = new ArrayList<>();

        while(filteredReceiptIterator.hasNext()) {
            if (totalPages == pageNum) {
                filteredReceipts.addAll(filteredReceiptIterator.next().getValue().stream()
                        .map(ConvertTableEntityToReceiptModelResponse::mapTableEntityToReceiptModelResponse)
                        .toList());
            } else {
                filteredReceiptIterator.next();
            }
            totalPages++;
        }

        if(totalPages <= pageNum) {
            throw new AppException(AppError.PAGE_NUMBER_GREATER_THAN_TOTAL_PAGES);
        }

        return PageInfo.builder()
                .receiptsList(filteredReceipts)
                .totalPages(totalPages)
                .build();
    }

    private static String getStartsWithFilter(String field, String startsWith) {
        int length = startsWith.length() - 1;
        int nextChar = startsWith.toCharArray()[length] + 1;

        String startWithEnd = startsWith.substring(0, length) + (char) nextChar;

        return String.format("%s ge '%s' and %s lt '%s'", field, startsWith, field, startWithEnd);
    }

    private PaymentsResult<ReceiptModelResponse> setReceiptsOutput(List<ReceiptModelResponse> listOfEntity, int totalPages, int pageNumber) {
        PaymentsResult<ReceiptModelResponse> result = new PaymentsResult<>();
        result.setResults(listOfEntity);
        result.setLength(listOfEntity.size());
        result.setCurrentPageNumber(pageNumber);
        result.setTotalPages(totalPages);
        return result;
    }
}
