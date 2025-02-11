package it.gov.pagopa.payments.mapper;

import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import javax.validation.Valid;

import it.gov.pagopa.payments.model.enumeration.ReceiptStatus;
import org.modelmapper.Converter;
import org.modelmapper.spi.MappingContext;

public class ConvertReceiptEntityToReceiptModelResponse
    implements Converter<ReceiptEntity, ReceiptModelResponse> {

  @Override
  public ReceiptModelResponse convert(
      MappingContext<ReceiptEntity, ReceiptModelResponse> mappingContext) {
    @Valid ReceiptEntity receipt = mappingContext.getSource();
    return ReceiptModelResponse.builder()
            .organizationFiscalCode(receipt.getOrganizationFiscalCode())
            .iuv(receipt.getIuv())
            .debtor(receipt.getDebtor())
            .paymentDateTime(receipt.getPaymentDateTime())
            .status(ReceiptStatus.valueOf(receipt.getStatus()))
            .build();
  }
}
