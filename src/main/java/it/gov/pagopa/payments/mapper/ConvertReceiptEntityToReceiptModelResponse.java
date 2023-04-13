package it.gov.pagopa.payments.mapper;

import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import javax.validation.Valid;
import org.modelmapper.Converter;
import org.modelmapper.spi.MappingContext;

public class ConvertReceiptEntityToReceiptModelResponse
    implements Converter<ReceiptEntity, ReceiptModelResponse> {

  @Override
  public ReceiptModelResponse convert(
      MappingContext<ReceiptEntity, ReceiptModelResponse> mappingContext) {
    @Valid ReceiptEntity re = mappingContext.getSource();
    return ReceiptModelResponse.builder()
        .organizationFiscalCode(re.getPartitionKey())
        .iuv(re.getRowKey())
        .debtor(re.getDebtor())
        .build();
  }
}
