package it.gov.pagopa.payments.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.xml.datatype.XMLGregorianCalendar;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class ReceiptEntityCosmos{

    private String organizationFiscalCode;
    private String iuv;
    private String debtor;
    private String document;
    private String status = Status.CREATED.name();
    private String paymentDateTime;

    public ReceiptEntityCosmos(String organizationFiscalCode, String iuv){
        this.organizationFiscalCode = organizationFiscalCode;
        this.iuv = iuv;
    }

}
