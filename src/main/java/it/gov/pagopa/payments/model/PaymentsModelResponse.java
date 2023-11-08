package it.gov.pagopa.payments.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@ToString
public class PaymentsModelResponse implements Serializable {

  private static final long serialVersionUID = -4897632346879847721L;

  private String iuv;
  private String organizationFiscalCode;
  private long amount;
  private String description;
  private Boolean isPartialPayment;
  private LocalDateTime dueDate;
  private LocalDateTime retentionDate;
  private LocalDateTime paymentDate;
  private LocalDateTime reportingDate;
  private LocalDateTime insertedDate;
  private String paymentMethod;
  private long fee;
  private String pspCompany;
  private String idReceipt;
  private String idFlowReporting;
  private PaymentOptionStatus status;
  private Type type;
  private String fiscalCode;
  private String fullName;
  private String streetName;
  private String civicNumber;
  private String postalCode;
  private String city;
  private String province;
  private String region;
  private String country;
  private String email;
  private String phone;
  private String companyName;
  private String officeName;
  private DebtPositionStatus debtPositionStatus;
  private List<PaymentsTransferModelResponse> transfer;
}
