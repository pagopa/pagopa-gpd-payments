package it.gov.pagopa.payments.controller.receipt.impl;

import it.gov.pagopa.payments.config.LogDetails;
import it.gov.pagopa.payments.config.LogEntity;
import it.gov.pagopa.payments.controller.receipt.IPaymentsController;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.PaymentsResult;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import it.gov.pagopa.payments.service.PaymentsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Arrays;

@Controller
@Validated
public class PaymentsController implements IPaymentsController {

    @Autowired
    private PaymentsService paymentsService;

    @Override
    public ResponseEntity<String> getReceiptByIUV(
            @LogEntity(name = "organizationFiscalCode") String organizationFiscalCode,
            @LogEntity(name = "iuv") String iuv,
            @LogDetails(name = "segregationCodes") String segregationCodes) {
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
            @LogEntity(name = "organizationFiscalCode") String organizationFiscalCode,
            int pageNum,
            int pageSize,
            String debtor,
            String service,
            String from,
            String to,
            @LogDetails(name = "segregationCodes") String segregationCodes,
            @LogDetails(name = "segregationCodes", mask = true) String debtorOrIuv) {

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
