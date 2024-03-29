package it.gov.pagopa.payments.controller.receipt.impl;

import it.gov.pagopa.payments.controller.receipt.IPaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.model.ReceiptsInfo;
import it.gov.pagopa.payments.service.PaymentsService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

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
  private static final String LOG_BASE_PARAMS_DETAIL = "organizationFiscalCode= %s";

  @Autowired private ModelMapper modelMapper;

  @Autowired private PaymentsService paymentsService;

  @Override
  public ResponseEntity<String> getReceiptByIUV(String organizationFiscalCode, String iuv, String segregationCodes) {
    log.info(String.format(LOG_BASE_HEADER_INFO, "GET", "getReceiptByIUV", String.format(LOG_BASE_PARAMS_DETAIL, organizationFiscalCode) + "; iuv= " + iuv
                + "; validSegregationCodes= " + segregationCodes));

    ArrayList<String> segCodesList = segregationCodes != null ? new ArrayList<>(Arrays.asList(segregationCodes.split(","))) : null;
    ReceiptEntity receipt = paymentsService
                                    .getReceiptByOrganizationFCAndIUV(organizationFiscalCode, iuv, segCodesList);
    return new ResponseEntity<>(receipt.getDocument(), HttpStatus.OK);
  }

  @Override
  public ResponseEntity<ReceiptsInfo> getOrganizationReceipts(String organizationFiscalCode, int pageNum, int pageSize, String debtor,
                                                              String service, String from, String to, String segregationCodes) {

    log.info(String.format(LOG_BASE_HEADER_INFO, "GET", "getOrganizationReceipts", String.format(LOG_BASE_PARAMS_DETAIL, organizationFiscalCode)
                + "; debtor= " + debtor + "; service= " + service + "; validSegregationCodes= " + segregationCodes));

    ArrayList<String> segCodesList = segregationCodes != null ? new ArrayList<>(Arrays.asList(segregationCodes.split(","))) : null;
    PaymentsResult<ReceiptEntity> receipts = paymentsService
                                                     .getOrganizationReceipts(organizationFiscalCode, debtor, service, from, to, pageNum, pageSize, segCodesList);
    return new ResponseEntity<>(
        ReceiptsInfo.builder().receiptsList(receipts.getResults().stream()
                    .map(receiptEntity -> modelMapper.map(receiptEntity, ReceiptModelResponse.class))
                    .collect(Collectors.toList())).build(), HttpStatus.OK);
  }
}
