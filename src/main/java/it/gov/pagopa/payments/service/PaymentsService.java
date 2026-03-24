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

import java.time.LocalDate;
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
            
            List<ReceiptModelResponse> checkedReceipts =
                    getGPDCheckedReceiptsList(filteredEntities.getReceiptsList());
            
            return this.setReceiptsOutput(checkedReceipts, filteredEntities.getTotalPages(), pageNum);

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
            this.checkGPDDebtPosStatus(tableEntity);
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

    public void checkGPDDebtPosStatus(TableEntity tableEntity) {
        try {
            Object statusProperty = tableEntity.getProperty(STATUS_PROPERTY);
            String status = statusProperty != null ? statusProperty.toString().trim() : null;

            // the check on GPD is necessary if the status of the receipt is different from PAID
            if (!Status.PAID.name().equalsIgnoreCase(status)) {
                PaymentsModelResponse paymentOption =
                        gpdClient.getPaymentOption(tableEntity.getPartitionKey(), tableEntity.getRowKey());

                if (paymentOption != null && paymentOption.getStatus().equals(PaymentOptionStatus.PO_UNPAID)) {
                    throw new AppException(
                            AppError.UNPROCESSABLE_RECEIPT,
                            paymentOption.getStatus(),
                            tableEntity.getPartitionKey(),
                            tableEntity.getRowKey());
                }

                // if no exception is raised the status on GPD is correctly in PAID -> for congruence update receipt status
                tableEntity.addProperty(STATUS_PROPERTY, Status.PAID.name());
                tableClient.updateEntity(tableEntity);
            }
        } catch (TableServiceException e) {
            throw new AppException(AppError.DB_ERROR, "Error when updating receipt status");
        }
    }
    
    public List<ReceiptModelResponse> getGPDCheckedReceiptsList(List<TableEntity> result) {
        // for all the receipts in the azure table, only those that have been already PAID status or are
        // in PAID status on GPD are returned
        List<ReceiptModelResponse> checkedReceipts = new ArrayList<>();

        for (TableEntity tableEntity : result) {
            try {
                this.checkGPDDebtPosStatus(tableEntity);
                checkedReceipts.add(
                        ConvertTableEntityToReceiptModelResponse.mapTableEntityToReceiptModelResponse(tableEntity));
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
    
    public PageInfo retrieveEntitiesByFilter(TableClient tableClient,
    		String organizationFiscalCode,
    		String debtor,
    		String service,
    		String from,
    		String to,
    		int pageNum,
    		int pageSize,
    		List<String> segCodes,
    		String debtorOrIuv) {

    	List<String> filters = new ArrayList<>();

    	filters.add(String.format("PartitionKey eq '%s'", organizationFiscalCode));

    	if (debtor != null) {
    		filters.add(String.format("debtor eq '%s'", debtor));
    	}

    	if (service != null) {
    		filters.add(getStartsWithFilter(ROWKEY_PROPERTY, service));
    	}

    	String[] normalizedDateRange = normalizeDateRange(organizationFiscalCode, from, to);
    	if (normalizedDateRange != null) {
    	    filters.add(String.format(
    	            "paymentDate ge '%s' and paymentDate le '%s'",
    	            normalizedDateRange[0],
    	            normalizedDateRange[1]));
    	}

    	if (debtorOrIuv != null) {
    		String iuvFilter = getStartsWithFilter(ROWKEY_PROPERTY, debtorOrIuv);
    		String debtorFilter = getStartsWithFilter(DEBTOR_PROPERTY, debtorOrIuv);
    		filters.add('(' + String.join(" or ", '(' + iuvFilter + ')', '(' + debtorFilter + ')') + ')');
    	}

    	String filter = String.join(" and ", filters);

    	if (segCodes != null && !segCodes.isEmpty()) {
    		List<String> segCodesFilters = new ArrayList<>();
    		for (String segCode : segCodes) {
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
    	List<TableEntity> filteredReceipts = Collections.emptyList();

    	while (filteredReceiptIterator.hasNext()) {
    		PagedResponse<TableEntity> page = filteredReceiptIterator.next();

    		if (totalPages == pageNum) {
    			filteredReceipts = page.getValue();
    		}

    		totalPages++;
    	}

    	if (totalPages <= pageNum) {
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
    
    private String[] normalizeDateRange(String organizationFiscalCode, String from, String to) {
        if (from == null && to == null) {
            return null;
        }

        LocalDate today = LocalDate.now();
        LocalDate resolvedFrom;
        LocalDate resolvedTo;

        if (from != null && to != null) {
            resolvedFrom = LocalDate.parse(from);
            resolvedTo = LocalDate.parse(to);
        } else if (from != null) {
            resolvedFrom = LocalDate.parse(from);
            resolvedTo = today;
        } else {
            resolvedTo = LocalDate.parse(to);
            resolvedFrom = LocalDate.of(1970, 1, 1);
        }

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new AppException(AppError.RETRIEVAL_RECEIPTS_FAILED, organizationFiscalCode + "with 'from' after 'to' date filters");
        }

        return new String[] {
                resolvedFrom.toString() + "T00:00:00",
                resolvedTo.toString() + "T23:59:59"
            };
    }
}
