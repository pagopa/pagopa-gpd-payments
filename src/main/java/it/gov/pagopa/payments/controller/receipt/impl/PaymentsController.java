package it.gov.pagopa.payments.controller.receipt.impl;

import it.gov.pagopa.payments.config.LogField;
import it.gov.pagopa.payments.config.LogContext;
import it.gov.pagopa.payments.controller.receipt.IPaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.service.PaymentsService;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

@Controller
@Validated
public class PaymentsController implements IPaymentsController {
  private static final String ARG_SEGREGATION_CODES = "segregationCodes";
  private static final String ARG_PAGE_NUMBER = "pageNum";
  private static final String ARG_PAGE_SIZE = "pageSize";
  private static final String ARG_SERVICE = "service";
  private static final String ARG_FROM = "from";
  private static final String ARG_TO = "to";
  private static final String SEGREGATION_CODES_SEPARATOR = ",";

  @Autowired private PaymentsService paymentsService;

  @Override
  public ResponseEntity<String> getReceiptByIUV(
      @LogField(LogContext.CTX_CREDITOR_ID) String organizationFiscalCode,
      @LogField(LogContext.CTX_IUV) String iuv,
      @LogField(value = ARG_SEGREGATION_CODES, argument = true) String segregationCodes) {

    ArrayList<String> segCodesList =
        segregationCodes != null
            ? new ArrayList<>(Arrays.asList(segregationCodes.split(SEGREGATION_CODES_SEPARATOR)))
            : null;
    ReceiptEntity receipt =
        paymentsService.getReceiptByOrganizationFCAndIUV(organizationFiscalCode, iuv, segCodesList);
    return new ResponseEntity<>(receipt.getDocument(), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<PaymentsResult<ReceiptModelResponse>> getOrganizationReceipts(
      @LogField(LogContext.CTX_CREDITOR_ID) String organizationFiscalCode,
      @LogField(value = ARG_PAGE_NUMBER, argument = true) int pageNum,
      @LogField(value = ARG_PAGE_SIZE, argument = true) int pageSize,
      String debtor,
      @LogField(value = ARG_SERVICE, argument = true) String service,
      @LogField(value = ARG_FROM, argument = true) String from,
      @LogField(value = ARG_TO, argument = true) String to,
      @LogField(value = ARG_SEGREGATION_CODES, argument = true) String segregationCodes,
      String debtorOrIuv) {

    ArrayList<String> segCodesList =
        segregationCodes != null
            ? new ArrayList<>(Arrays.asList(segregationCodes.split(SEGREGATION_CODES_SEPARATOR)))
            : null;
    PaymentsResult<ReceiptModelResponse> receipts =
        paymentsService.getOrganizationReceipts(
            organizationFiscalCode,
            debtor,
            service,
            from,
            to,
            pageNum,
            pageSize,
            segCodesList,
            debtorOrIuv);
    return new ResponseEntity<>(receipts, HttpStatus.OK);
  }
}
