package it.gov.pagopa.payments.controller.receipt.impl;

import static it.gov.pagopa.payments.utils.CommonUtil.sanitizeInput;

import it.gov.pagopa.payments.controller.receipt.IPaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.service.PaymentsService;
import java.util.ArrayList;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

@Controller
@Slf4j
@Validated
public class PaymentsController implements IPaymentsController {

  private static final String LOG_BASE_HEADER_INFO =
      "[RequestMethod: %s] - [ClassMethod: %s] - [MethodParamsToLog: %s]";
  private static final String LOG_BASE_PARAMS_DETAIL = "organizationFiscalCode= %s";

  private final PaymentsService paymentsService;

  @Autowired
  public PaymentsController(PaymentsService paymentsService) {
    this.paymentsService = paymentsService;
  }

  @Override
  public ResponseEntity<String> getReceiptByIUV(
      String organizationFiscalCode, String iuv, String segregationCodes) {
    String sanitizedOrganizationFiscalCode = sanitizeInput(organizationFiscalCode);
    String sanitizedIuv = sanitizeInput(iuv);
    log.debug(
        String.format(
            LOG_BASE_HEADER_INFO,
            "GET",
            String.format(LOG_BASE_PARAMS_DETAIL, sanitizedOrganizationFiscalCode)
                + "; iuv= "
                + sanitizedIuv
                + "; validSegregationCodes= "
                + segregationCodes));

    ArrayList<String> segCodesList =
        segregationCodes != null
            ? new ArrayList<>(Arrays.asList(segregationCodes.split(",")))
            : null;
    ReceiptEntity receipt =
        paymentsService.getReceiptByOrganizationFCAndIUV(organizationFiscalCode, iuv, segCodesList);
    return new ResponseEntity<>(receipt.getDocument(), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<PaymentsResult<ReceiptModelResponse>> getOrganizationReceipts(
      String organizationFiscalCode,
      int pageNum,
      int pageSize,
      String debtor,
      String service,
      String from,
      String to,
      String segregationCodes,
      String debtorOrIuv) {

    log.debug(
        String.format(
            LOG_BASE_HEADER_INFO,
            "GET",
            "getOrganizationReceipts",
            String.format(
                LOG_BASE_PARAMS_DETAIL,
                sanitizeInput(organizationFiscalCode)
                    + "; debtor= "
                    + sanitizeInput(debtor)
                    + "; service= "
                    + sanitizeInput(service)
                    + "; validSegregationCodes= "
                    + sanitizeInput(segregationCodes))));

    ArrayList<String> segCodesList =
        segregationCodes != null
            ? new ArrayList<>(Arrays.asList(segregationCodes.split(",")))
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
