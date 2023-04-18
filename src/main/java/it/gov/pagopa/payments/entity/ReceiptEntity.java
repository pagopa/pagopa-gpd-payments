package it.gov.pagopa.payments.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class ReceiptEntity {

    private String organizationFiscalCode;
    private String iuv;
    private String debtor;
    private String document;
    private String status = Status.CREATED.name();
    private String paymentDateTime;

    public ReceiptEntity(String organizationFiscalCode, String iuv){
        this.organizationFiscalCode = organizationFiscalCode;
        this.iuv = iuv;
    }

}
