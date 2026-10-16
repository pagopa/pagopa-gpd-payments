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
import it.gov.pagopa.payments.logging.EventDetails;
import it.gov.pagopa.payments.logging.LogEventWriter;
import it.gov.pagopa.payments.logging.FailureDiagnostics;
import it.gov.pagopa.payments.logging.ContextSnapshot;
import it.gov.pagopa.payments.logging.OperationLogScope;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptEntity;
import it.gov.pagopa.payments.mapper.ConvertTableEntityToReceiptModelResponse;
import it.gov.pagopa.payments.model.*;
import it.gov.pagopa.payments.client.GpdClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@Slf4j
public class PaymentsService {

    private static final LogEventWriter LOG_EVENTS = new LogEventWriter(log);

    public static final String STATUS_PROPERTY = "status";

    public static final String ROWKEY_PROPERTY = "RowKey";

    public static final String DEBTOR_PROPERTY = "debtor";

    @Autowired private TableClient tableClient;
    @Autowired
    private GpdClient gpdClient;
    
    @Value("${payments.receipts.months-window:3}")
    private int receiptsMonthsWindow;

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
        enrichCreditor(organizationFiscalCode);
        try {
        	
        	String[] normalizedDateRange = normalizeDateRange(from, to);
            String normalizedFrom = normalizedDateRange[0];
            String normalizedTo = normalizedDateRange[1];
        	
            PageInfo filteredEntities = retrieveEntitiesByFilter(tableClient,
                    organizationFiscalCode, debtor, service, normalizedFrom, normalizedTo, pageNum, pageSize, segCodes, debtorOrIuv);
            
            List<ReceiptModelResponse> checkedReceipts =
                    getGPDCheckedReceiptsList(filteredEntities.getReceiptsList());
            
            return this.setReceiptsOutput(checkedReceipts, filteredEntities.getTotalPages(), pageNum);

        } catch (TableServiceException e) {
            throw new AppException(AppError.DB_ERROR, e, "ALL");
        }
    }

    public ReceiptEntity getReceiptByOrganizationFCAndIUV(
            @NotBlank String organizationFiscalCode, @NotBlank String iuv, List<String> validSegregationCodes) {

        enrichCreditor(organizationFiscalCode);
        try {
            if(validSegregationCodes != null && !validSegregationCodes.isEmpty() && iuv.length() > 1) {
                String iuvSegregationCode = iuv.substring(0,2);
                if(!isBrokerAuthorized(iuvSegregationCode, validSegregationCodes))
                    throw new AppException(AppError.FORBIDDEN_SEGREGATION_CODE, iuvSegregationCode, organizationFiscalCode, iuv);
            }

            TableEntity tableEntity = getReceiptEntity(organizationFiscalCode, iuv);
            this.checkGPDDebtPosStatus(tableEntity);
            return ConvertTableEntityToReceiptEntity.mapTableEntityToReceiptEntity(tableEntity);
        } catch (TableServiceException e) {
            if(e.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND){
                throw new AppException(AppError.RECEIPT_NOT_FOUND, e, organizationFiscalCode, iuv);
            }
            throw new AppException(AppError.DB_ERROR, e);
        }
    }

    private TableEntity getReceiptEntity(String organizationFiscalCode, String iuv) {
        long startedNanos = System.nanoTime();
        try {
            return tableClient.getEntity(organizationFiscalCode, iuv);
        } catch (RuntimeException e) {
            if (!(e instanceof TableServiceException tableFailure
                    && tableFailure.getValue() != null
                    && tableFailure.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND)) {
                FailureDiagnostics.dependencyFailure(EventDetails.Dependency.AZURE_TABLE_STORAGE,
                        EventDetails.DependencyOperation.GET_RECEIPT, startedNanos, e);
            }
            throw e;
        }
    }

    public void checkGPDDebtPosStatus(TableEntity tableEntity) {
        enrichCreditor(tableEntity.getPartitionKey());
        try {
            Object statusProperty = tableEntity.getProperty(STATUS_PROPERTY);
            String status = statusProperty != null ? statusProperty.toString().trim() : null;

            // the check on GPD is necessary if the status of the receipt is different from PAID
            if (!Status.PAID.name().equalsIgnoreCase(status)) {
                PaymentsModelResponse paymentOption =
                        getPaymentOption(tableEntity.getPartitionKey(), tableEntity.getRowKey());

                if (paymentOption != null && paymentOption.getStatus().equals(PaymentOptionStatus.PO_UNPAID)) {
                    throw new AppException(
                            AppError.UNPROCESSABLE_RECEIPT,
                            paymentOption.getStatus(),
                            tableEntity.getPartitionKey(),
                            tableEntity.getRowKey());
                }

                // if no exception is raised the status on GPD is correctly in PAID -> for congruence update receipt status
                tableEntity.addProperty(STATUS_PROPERTY, Status.PAID.name());
                updateReceiptStatus(tableEntity);
            }
        } catch (TableServiceException e) {
            throw new AppException(AppError.DB_ERROR, e, "Error when updating receipt status");
        }
    }

    private PaymentsModelResponse getPaymentOption(String organizationFiscalCode, String rowKey) {
        long startedNanos = System.nanoTime();
        try {
            return gpdClient.getPaymentOption(organizationFiscalCode, rowKey);
        } catch (FeignException.NotFound e) {
            throw e;
        } catch (RuntimeException e) {
            FailureDiagnostics.dependencyFailure(EventDetails.Dependency.GPD_CORE,
                    EventDetails.DependencyOperation.GET_PAYMENT_OPTION, startedNanos, e);
            throw e;
        }
    }

    private void updateReceiptStatus(TableEntity tableEntity) {
        long startedNanos = System.nanoTime();
        try {
            tableClient.updateEntity(tableEntity);
        } catch (RuntimeException e) {
            FailureDiagnostics.dependencyFailure(EventDetails.Dependency.AZURE_TABLE_STORAGE,
                    EventDetails.DependencyOperation.UPDATE_RECEIPT_STATUS, startedNanos, e);
            throw e;
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
                // Unknown receipts remain omitted from the list.
            } catch (AppException e) {
                if (e.getHttpStatus().is5xxServerError()) {
                    FailureDiagnostics.absorbedFailure(LOG_EVENTS, e);
                }
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

        enrichCreditor(organizationFiscalCode);
    	List<String> filters = new ArrayList<>();

    	filters.add(String.format("PartitionKey eq '%s'", organizationFiscalCode));

    	if (debtor != null) {
    		filters.add(String.format("debtor eq '%s'", debtor));
    	}

    	if (service != null) {
    		filters.add(getStartsWithFilter(ROWKEY_PROPERTY, service));
    	}

    	if (from != null && to != null) {
    		filters.add(String.format("paymentDate ge '%s' and paymentDate le '%s'", from, to));
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

        int totalPages = 0;
        List<TableEntity> filteredReceipts = Collections.emptyList();
        long startedNanos = System.nanoTime();
        try {
            Iterator<PagedResponse<TableEntity>> filteredReceiptIterator = tableClient
                    .listEntities(
                            new ListEntitiesOptions()
                                    .setFilter(filter)
                                    .setTop(pageSize),
                            null,
                            null)
                    .iterableByPage()
                    .iterator();

            while (filteredReceiptIterator.hasNext()) {
                PagedResponse<TableEntity> page = filteredReceiptIterator.next();

                if (totalPages == pageNum) {
                    filteredReceipts = page.getValue();
                }

                totalPages++;
            }
        } catch (RuntimeException e) {
            FailureDiagnostics.dependencyFailure(EventDetails.Dependency.AZURE_TABLE_STORAGE,
                    EventDetails.DependencyOperation.LIST_RECEIPTS, startedNanos, e);
            throw e;
        }

    	if (totalPages <= pageNum) {
    		throw new AppException(AppError.PAGE_NUMBER_GREATER_THAN_TOTAL_PAGES);
    	}

    	return PageInfo.builder()
    			.receiptsList(filteredReceipts)
    			.totalPages(totalPages)
    			.build();
    }
    
    private boolean isBrokerAuthorized(String iuvSegregationCode, List<String> brokerSegregationCodes) {
        // verify that IUV is linked with one of segregation code for which the broker is authorized
        for(String code: brokerSegregationCodes) {
            if(code.equals(iuvSegregationCode))
                return true;
        }

        return false;
    }

    private static void enrichCreditor(String organizationFiscalCode) {
        OperationLogScope scope = OperationLogScope.currentOrNull();
        if (scope != null) {
            scope.setPaymentLogContext(ContextSnapshot.capture().noticeNumber(), organizationFiscalCode);
        }
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
    
    private String[] normalizeDateRange(String from, String to) {
        LocalDateTime today = LocalDateTime.now(ZoneId.systemDefault());

        String normalizedFromInput = normalizeDateInput(from);
        String normalizedToInput = normalizeDateInput(to);

        // No explicit range provided: use the configured default window.
        if (normalizedFromInput == null && normalizedToInput == null) {
            return toDateRange(today.minusMonths(receiptsMonthsWindow), today);
        }

        // Explicit full range provided: use it as-is, preserving the provided precision.
        if (normalizedFromInput != null && normalizedToInput != null) {
            LocalDateTime resolvedFrom = parseSupportedDateTime(normalizedFromInput, true);
            LocalDateTime resolvedTo = parseSupportedDateTime(normalizedToInput, false);
            validateDateRange(resolvedFrom, resolvedTo);
            return toDateRange(resolvedFrom, resolvedTo);
        }

        // Only "from" provided: complete the range using the configured window.
        if (normalizedFromInput != null) {
            LocalDateTime resolvedFrom = parseSupportedDateTime(normalizedFromInput, true);
            LocalDateTime resolvedTo = resolvedFrom.plusMonths(receiptsMonthsWindow);

            if (resolvedTo.isAfter(today)) {
                resolvedTo = today;
            }

            return toDateRange(resolvedFrom, resolvedTo);
        }

        // Only "to" provided: complete the range using the configured window.
        LocalDateTime resolvedTo = parseSupportedDateTime(normalizedToInput, false);
        LocalDateTime resolvedFrom = resolvedTo.minusMonths(receiptsMonthsWindow);
        return toDateRange(resolvedFrom, resolvedTo);
    }
    
    private LocalDateTime parseSupportedDateTime(String value, boolean isFromBoundary) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            try {
                LocalDate parsedDate = LocalDate.parse(value);
                return isFromBoundary
                        ? parsedDate.atStartOfDay()
                        : parsedDate.atTime(23, 59, 59);
            } catch (DateTimeParseException innerEx) {
                throw new AppException(AppError.INVALID_DATE_RANGE);
            }
        }
    }

    private String normalizeDateInput(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private void validateDateRange(LocalDateTime from, LocalDateTime to) {
        if (from.isAfter(to)) {
            throw new AppException(AppError.INVALID_DATE_RANGE);
        }
    }

    private String[] toDateRange(LocalDateTime from, LocalDateTime to) {
        return new String[] {
            from.toString(),
            to.toString()
        };
    }


}
