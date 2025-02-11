package it.gov.pagopa.payments.controller.receipt.impl;

import static it.gov.pagopa.payments.utils.CommonUtil.sanitizeInput;

import it.gov.pagopa.payments.controller.receipt.IPaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.service.PaymentsService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
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

  @Autowired private ModelMapper modelMapper;

  @Autowired private PaymentsService paymentsService;

  @Override
  public ResponseEntity<String> getReceiptByIUV(
      String organizationFiscalCode, String iuv, String segregationCodes) {
    String sanitizedOrganizationFiscalCode = sanitizeInput(organizationFiscalCode);
    String sanitizedIuv = sanitizeInput(iuv);
    String sanitizedSegregationCodes = sanitizeInput(segregationCodes);
    log.debug(
        String.format(
            LOG_BASE_HEADER_INFO,
            "GET",
            "getReceiptByIUV",
            "organizationFiscalCode="
                + sanitizedOrganizationFiscalCode
                + "; iuv= "
                + sanitizedIuv
                + "; validSegregationCodes= "
                + sanitizedSegregationCodes));

    ArrayList<String> segCodesList =
        segregationCodes != null
            ? new ArrayList<>(Arrays.asList(segregationCodes.split(",")))
            : null;
    ReceiptEntity receipt =
        paymentsService.getReceiptByOrganizationFCAndIUV(organizationFiscalCode, iuv, segCodesList);
    return new ResponseEntity<>(receipt.getDocument(), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<PaymentsResult> getOrganizationReceipts(
      String organizationFiscalCode,
      int pageNum,
      int pageSize,
      String debtor,
      String service,
      LocalDateTime from,
      LocalDateTime to,
      String segregationCodes,
      String debtorOrIuv) {

    String sanitizedSegregationCodes = sanitizeInput(segregationCodes);
    log.debug(
        String.format(
            LOG_BASE_HEADER_INFO,
            "GET",
            "getOrganizationReceipts",
            "organizationFiscalCode="
                + sanitizeInput(organizationFiscalCode)
                + "; debtor= "
                + sanitizeInput(debtor)
                + "; service= "
                + sanitizeInput(service)
                + "; validSegregationCodes= "
                + sanitizedSegregationCodes));

    ArrayList<String> segCodesList =
        segregationCodes != null
            ? new ArrayList<>(Arrays.asList(segregationCodes.split(",")))
            : null;
    PaymentsResult receipts =
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
