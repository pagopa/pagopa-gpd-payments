package it.gov.pagopa.payments.entity;

import it.gov.pagopa.payments.utils.Sensitive;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class ReceiptEntity {

    private String organizationFiscalCode;
    private String iuv;
    @Sensitive
    private String debtor;
    private String paymentDateTime;
    private String status = Status.PAID.name();
    private String document;

    public ReceiptEntity(String organizationFiscalCode, String iuv){
        this.organizationFiscalCode = organizationFiscalCode;
        this.iuv = iuv;
    }

}
