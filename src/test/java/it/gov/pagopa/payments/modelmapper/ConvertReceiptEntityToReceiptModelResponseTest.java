package it.gov.pagopa.payments.modelmapper;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import it.gov.pagopa.payments.PaymentsApplication;
import it.gov.pagopa.payments.entity.ReceiptEntity;
import it.gov.pagopa.payments.model.ReceiptModelResponse;
import org.junit.jupiter.api.Test;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = PaymentsApplication.class)
class ConvertReceiptEntityToReceiptModelResponseTest {

  @Autowired private ModelMapper modelMapper;

  @Test
  void convert() {
    ReceiptEntity re = new ReceiptEntity("org", "iuv");
    re.setDebtor("debtor");
    re.setDocument("XML");

    ReceiptModelResponse res = modelMapper.map(re, ReceiptModelResponse.class);
    assertNotNull(res);
    assertEquals(re.getOrganizationFiscalCode(), res.getOrganizationFiscalCode());
    assertEquals(re.getIuv(), res.getIuv());
  }
}
