package it.gov.pagopa.payments.entity;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class ReceiptEntity {

    private String organizationFiscalCode;
    private String iuv;
    private String debtor;
    private LocalDateTime paymentDateTime;
    private String status = Status.PAID.name();
    private String document;

    public ReceiptEntity(String organizationFiscalCode, String iuv){
        this.organizationFiscalCode = organizationFiscalCode;
        this.iuv = iuv;
    }

}
