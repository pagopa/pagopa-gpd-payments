package it.gov.pagopa.payments.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public class PaymentsModelResponse implements Serializable {

  private static final long serialVersionUID = -4897632346879847721L;

  private String nav;
  private String iuv;
  private String organizationFiscalCode;
  private long amount;
  private String description;
  private Boolean isPartialPayment;
  private Boolean payStandIn;
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
  private List<PaymentOptionMetadataModel> paymentOptionMetadata;
  private String serviceType;

  public PaymentsModelResponse() {
  }

  public String getNav() {
    return this.nav;
  }

  public String getIuv() {
    return this.iuv;
  }

  public String getOrganizationFiscalCode() {
    return this.organizationFiscalCode;
  }

  public long getAmount() {
    return this.amount;
  }

  public String getDescription() {
    return this.description;
  }

  public Boolean getIsPartialPayment() {
    return this.isPartialPayment;
  }

  public Boolean getPayStandIn() {
    return this.payStandIn;
  }

  public LocalDateTime getDueDate() {
    return this.dueDate;
  }

  public LocalDateTime getRetentionDate() {
    return this.retentionDate;
  }

  public LocalDateTime getPaymentDate() {
    return this.paymentDate;
  }

  public LocalDateTime getReportingDate() {
    return this.reportingDate;
  }

  public LocalDateTime getInsertedDate() {
    return this.insertedDate;
  }

  public String getPaymentMethod() {
    return this.paymentMethod;
  }

  public long getFee() {
    return this.fee;
  }

  public String getPspCompany() {
    return this.pspCompany;
  }

  public String getIdReceipt() {
    return this.idReceipt;
  }

  public String getIdFlowReporting() {
    return this.idFlowReporting;
  }

  public PaymentOptionStatus getStatus() {
    return this.status;
  }

  public Type getType() {
    return this.type;
  }

  public String getFiscalCode() {
    return this.fiscalCode;
  }

  public String getFullName() {
    return this.fullName;
  }

  public String getStreetName() {
    return this.streetName;
  }

  public String getCivicNumber() {
    return this.civicNumber;
  }

  public String getPostalCode() {
    return this.postalCode;
  }

  public String getCity() {
    return this.city;
  }

  public String getProvince() {
    return this.province;
  }

  public String getRegion() {
    return this.region;
  }

  public String getCountry() {
    return this.country;
  }

  public String getEmail() {
    return this.email;
  }

  public String getPhone() {
    return this.phone;
  }

  public String getCompanyName() {
    return this.companyName;
  }

  public String getOfficeName() {
    return this.officeName;
  }

  public DebtPositionStatus getDebtPositionStatus() {
    return this.debtPositionStatus;
  }

  public List<PaymentsTransferModelResponse> getTransfer() {
    return this.transfer;
  }

  public List<PaymentOptionMetadataModel> getPaymentOptionMetadata() {
    return this.paymentOptionMetadata;
  }

  public String getServiceType() {
    return this.serviceType;
  }

  public void setNav(String nav) {
    this.nav = nav;
  }

  public void setIuv(String iuv) {
    this.iuv = iuv;
  }

  public void setOrganizationFiscalCode(String organizationFiscalCode) {
    this.organizationFiscalCode = organizationFiscalCode;
  }

  public void setAmount(long amount) {
    this.amount = amount;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setIsPartialPayment(Boolean isPartialPayment) {
    this.isPartialPayment = isPartialPayment;
  }

  public void setPayStandIn(Boolean payStandIn) {
    this.payStandIn = payStandIn;
  }

  public void setDueDate(LocalDateTime dueDate) {
    this.dueDate = dueDate;
  }

  public void setRetentionDate(LocalDateTime retentionDate) {
    this.retentionDate = retentionDate;
  }

  public void setPaymentDate(LocalDateTime paymentDate) {
    this.paymentDate = paymentDate;
  }

  public void setReportingDate(LocalDateTime reportingDate) {
    this.reportingDate = reportingDate;
  }

  public void setInsertedDate(LocalDateTime insertedDate) {
    this.insertedDate = insertedDate;
  }

  public void setPaymentMethod(String paymentMethod) {
    this.paymentMethod = paymentMethod;
  }

  public void setFee(long fee) {
    this.fee = fee;
  }

  public void setPspCompany(String pspCompany) {
    this.pspCompany = pspCompany;
  }

  public void setIdReceipt(String idReceipt) {
    this.idReceipt = idReceipt;
  }

  public void setIdFlowReporting(String idFlowReporting) {
    this.idFlowReporting = idFlowReporting;
  }

  public void setStatus(PaymentOptionStatus status) {
    this.status = status;
  }

  public void setType(Type type) {
    this.type = type;
  }

  public void setFiscalCode(String fiscalCode) {
    this.fiscalCode = fiscalCode;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public void setStreetName(String streetName) {
    this.streetName = streetName;
  }

  public void setCivicNumber(String civicNumber) {
    this.civicNumber = civicNumber;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public void setProvince(String province) {
    this.province = province;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }

  public void setOfficeName(String officeName) {
    this.officeName = officeName;
  }

  public void setDebtPositionStatus(DebtPositionStatus debtPositionStatus) {
    this.debtPositionStatus = debtPositionStatus;
  }

  public void setTransfer(List<PaymentsTransferModelResponse> transfer) {
    this.transfer = transfer;
  }

  public void setPaymentOptionMetadata(List<PaymentOptionMetadataModel> paymentOptionMetadata) {
    this.paymentOptionMetadata = paymentOptionMetadata;
  }

  public void setServiceType(String serviceType) {
    this.serviceType = serviceType;
  }

  public boolean equals(final Object o) {
    if (o == this) return true;
    if (!(o instanceof PaymentsModelResponse)) return false;
    final PaymentsModelResponse other = (PaymentsModelResponse) o;
    if (!other.canEqual((Object) this)) return false;
    final Object this$nav = this.getNav();
    final Object other$nav = other.getNav();
    if (this$nav == null ? other$nav != null : !this$nav.equals(other$nav)) return false;
    final Object this$iuv = this.getIuv();
    final Object other$iuv = other.getIuv();
    if (this$iuv == null ? other$iuv != null : !this$iuv.equals(other$iuv)) return false;
    final Object this$organizationFiscalCode = this.getOrganizationFiscalCode();
    final Object other$organizationFiscalCode = other.getOrganizationFiscalCode();
    if (this$organizationFiscalCode == null ? other$organizationFiscalCode != null : !this$organizationFiscalCode.equals(other$organizationFiscalCode))
      return false;
    if (this.getAmount() != other.getAmount()) return false;
    final Object this$description = this.getDescription();
    final Object other$description = other.getDescription();
    if (this$description == null ? other$description != null : !this$description.equals(other$description))
      return false;
    final Object this$isPartialPayment = this.getIsPartialPayment();
    final Object other$isPartialPayment = other.getIsPartialPayment();
    if (this$isPartialPayment == null ? other$isPartialPayment != null : !this$isPartialPayment.equals(other$isPartialPayment))
      return false;
    final Object this$payStandIn = this.getPayStandIn();
    final Object other$payStandIn = other.getPayStandIn();
    if (this$payStandIn == null ? other$payStandIn != null : !this$payStandIn.equals(other$payStandIn)) return false;
    final Object this$dueDate = this.getDueDate();
    final Object other$dueDate = other.getDueDate();
    if (this$dueDate == null ? other$dueDate != null : !this$dueDate.equals(other$dueDate)) return false;
    final Object this$retentionDate = this.getRetentionDate();
    final Object other$retentionDate = other.getRetentionDate();
    if (this$retentionDate == null ? other$retentionDate != null : !this$retentionDate.equals(other$retentionDate))
      return false;
    final Object this$paymentDate = this.getPaymentDate();
    final Object other$paymentDate = other.getPaymentDate();
    if (this$paymentDate == null ? other$paymentDate != null : !this$paymentDate.equals(other$paymentDate))
      return false;
    final Object this$reportingDate = this.getReportingDate();
    final Object other$reportingDate = other.getReportingDate();
    if (this$reportingDate == null ? other$reportingDate != null : !this$reportingDate.equals(other$reportingDate))
      return false;
    final Object this$insertedDate = this.getInsertedDate();
    final Object other$insertedDate = other.getInsertedDate();
    if (this$insertedDate == null ? other$insertedDate != null : !this$insertedDate.equals(other$insertedDate))
      return false;
    final Object this$paymentMethod = this.getPaymentMethod();
    final Object other$paymentMethod = other.getPaymentMethod();
    if (this$paymentMethod == null ? other$paymentMethod != null : !this$paymentMethod.equals(other$paymentMethod))
      return false;
    if (this.getFee() != other.getFee()) return false;
    final Object this$pspCompany = this.getPspCompany();
    final Object other$pspCompany = other.getPspCompany();
    if (this$pspCompany == null ? other$pspCompany != null : !this$pspCompany.equals(other$pspCompany)) return false;
    final Object this$idReceipt = this.getIdReceipt();
    final Object other$idReceipt = other.getIdReceipt();
    if (this$idReceipt == null ? other$idReceipt != null : !this$idReceipt.equals(other$idReceipt)) return false;
    final Object this$idFlowReporting = this.getIdFlowReporting();
    final Object other$idFlowReporting = other.getIdFlowReporting();
    if (this$idFlowReporting == null ? other$idFlowReporting != null : !this$idFlowReporting.equals(other$idFlowReporting))
      return false;
    final Object this$status = this.getStatus();
    final Object other$status = other.getStatus();
    if (this$status == null ? other$status != null : !this$status.equals(other$status)) return false;
    final Object this$type = this.getType();
    final Object other$type = other.getType();
    if (this$type == null ? other$type != null : !this$type.equals(other$type)) return false;
    final Object this$fiscalCode = this.getFiscalCode();
    final Object other$fiscalCode = other.getFiscalCode();
    if (this$fiscalCode == null ? other$fiscalCode != null : !this$fiscalCode.equals(other$fiscalCode)) return false;
    final Object this$fullName = this.getFullName();
    final Object other$fullName = other.getFullName();
    if (this$fullName == null ? other$fullName != null : !this$fullName.equals(other$fullName)) return false;
    final Object this$streetName = this.getStreetName();
    final Object other$streetName = other.getStreetName();
    if (this$streetName == null ? other$streetName != null : !this$streetName.equals(other$streetName)) return false;
    final Object this$civicNumber = this.getCivicNumber();
    final Object other$civicNumber = other.getCivicNumber();
    if (this$civicNumber == null ? other$civicNumber != null : !this$civicNumber.equals(other$civicNumber))
      return false;
    final Object this$postalCode = this.getPostalCode();
    final Object other$postalCode = other.getPostalCode();
    if (this$postalCode == null ? other$postalCode != null : !this$postalCode.equals(other$postalCode)) return false;
    final Object this$city = this.getCity();
    final Object other$city = other.getCity();
    if (this$city == null ? other$city != null : !this$city.equals(other$city)) return false;
    final Object this$province = this.getProvince();
    final Object other$province = other.getProvince();
    if (this$province == null ? other$province != null : !this$province.equals(other$province)) return false;
    final Object this$region = this.getRegion();
    final Object other$region = other.getRegion();
    if (this$region == null ? other$region != null : !this$region.equals(other$region)) return false;
    final Object this$country = this.getCountry();
    final Object other$country = other.getCountry();
    if (this$country == null ? other$country != null : !this$country.equals(other$country)) return false;
    final Object this$email = this.getEmail();
    final Object other$email = other.getEmail();
    if (this$email == null ? other$email != null : !this$email.equals(other$email)) return false;
    final Object this$phone = this.getPhone();
    final Object other$phone = other.getPhone();
    if (this$phone == null ? other$phone != null : !this$phone.equals(other$phone)) return false;
    final Object this$companyName = this.getCompanyName();
    final Object other$companyName = other.getCompanyName();
    if (this$companyName == null ? other$companyName != null : !this$companyName.equals(other$companyName))
      return false;
    final Object this$officeName = this.getOfficeName();
    final Object other$officeName = other.getOfficeName();
    if (this$officeName == null ? other$officeName != null : !this$officeName.equals(other$officeName)) return false;
    final Object this$debtPositionStatus = this.getDebtPositionStatus();
    final Object other$debtPositionStatus = other.getDebtPositionStatus();
    if (this$debtPositionStatus == null ? other$debtPositionStatus != null : !this$debtPositionStatus.equals(other$debtPositionStatus))
      return false;
    final Object this$transfer = this.getTransfer();
    final Object other$transfer = other.getTransfer();
    if (this$transfer == null ? other$transfer != null : !this$transfer.equals(other$transfer)) return false;
    final Object this$paymentOptionMetadata = this.getPaymentOptionMetadata();
    final Object other$paymentOptionMetadata = other.getPaymentOptionMetadata();
    if (this$paymentOptionMetadata == null ? other$paymentOptionMetadata != null : !this$paymentOptionMetadata.equals(other$paymentOptionMetadata))
      return false;
    final Object this$serviceType = this.getServiceType();
    final Object other$serviceType = other.getServiceType();
    if (this$serviceType == null ? other$serviceType != null : !this$serviceType.equals(other$serviceType))
      return false;
    return true;
  }

  protected boolean canEqual(final Object other) {
    return other instanceof PaymentsModelResponse;
  }

  public int hashCode() {
    final int PRIME = 59;
    int result = 1;
    final Object $nav = this.getNav();
    result = result * PRIME + ($nav == null ? 43 : $nav.hashCode());
    final Object $iuv = this.getIuv();
    result = result * PRIME + ($iuv == null ? 43 : $iuv.hashCode());
    final Object $organizationFiscalCode = this.getOrganizationFiscalCode();
    result = result * PRIME + ($organizationFiscalCode == null ? 43 : $organizationFiscalCode.hashCode());
    final long $amount = this.getAmount();
    result = result * PRIME + (int) ($amount >>> 32 ^ $amount);
    final Object $description = this.getDescription();
    result = result * PRIME + ($description == null ? 43 : $description.hashCode());
    final Object $isPartialPayment = this.getIsPartialPayment();
    result = result * PRIME + ($isPartialPayment == null ? 43 : $isPartialPayment.hashCode());
    final Object $payStandIn = this.getPayStandIn();
    result = result * PRIME + ($payStandIn == null ? 43 : $payStandIn.hashCode());
    final Object $dueDate = this.getDueDate();
    result = result * PRIME + ($dueDate == null ? 43 : $dueDate.hashCode());
    final Object $retentionDate = this.getRetentionDate();
    result = result * PRIME + ($retentionDate == null ? 43 : $retentionDate.hashCode());
    final Object $paymentDate = this.getPaymentDate();
    result = result * PRIME + ($paymentDate == null ? 43 : $paymentDate.hashCode());
    final Object $reportingDate = this.getReportingDate();
    result = result * PRIME + ($reportingDate == null ? 43 : $reportingDate.hashCode());
    final Object $insertedDate = this.getInsertedDate();
    result = result * PRIME + ($insertedDate == null ? 43 : $insertedDate.hashCode());
    final Object $paymentMethod = this.getPaymentMethod();
    result = result * PRIME + ($paymentMethod == null ? 43 : $paymentMethod.hashCode());
    final long $fee = this.getFee();
    result = result * PRIME + (int) ($fee >>> 32 ^ $fee);
    final Object $pspCompany = this.getPspCompany();
    result = result * PRIME + ($pspCompany == null ? 43 : $pspCompany.hashCode());
    final Object $idReceipt = this.getIdReceipt();
    result = result * PRIME + ($idReceipt == null ? 43 : $idReceipt.hashCode());
    final Object $idFlowReporting = this.getIdFlowReporting();
    result = result * PRIME + ($idFlowReporting == null ? 43 : $idFlowReporting.hashCode());
    final Object $status = this.getStatus();
    result = result * PRIME + ($status == null ? 43 : $status.hashCode());
    final Object $type = this.getType();
    result = result * PRIME + ($type == null ? 43 : $type.hashCode());
    final Object $fiscalCode = this.getFiscalCode();
    result = result * PRIME + ($fiscalCode == null ? 43 : $fiscalCode.hashCode());
    final Object $fullName = this.getFullName();
    result = result * PRIME + ($fullName == null ? 43 : $fullName.hashCode());
    final Object $streetName = this.getStreetName();
    result = result * PRIME + ($streetName == null ? 43 : $streetName.hashCode());
    final Object $civicNumber = this.getCivicNumber();
    result = result * PRIME + ($civicNumber == null ? 43 : $civicNumber.hashCode());
    final Object $postalCode = this.getPostalCode();
    result = result * PRIME + ($postalCode == null ? 43 : $postalCode.hashCode());
    final Object $city = this.getCity();
    result = result * PRIME + ($city == null ? 43 : $city.hashCode());
    final Object $province = this.getProvince();
    result = result * PRIME + ($province == null ? 43 : $province.hashCode());
    final Object $region = this.getRegion();
    result = result * PRIME + ($region == null ? 43 : $region.hashCode());
    final Object $country = this.getCountry();
    result = result * PRIME + ($country == null ? 43 : $country.hashCode());
    final Object $email = this.getEmail();
    result = result * PRIME + ($email == null ? 43 : $email.hashCode());
    final Object $phone = this.getPhone();
    result = result * PRIME + ($phone == null ? 43 : $phone.hashCode());
    final Object $companyName = this.getCompanyName();
    result = result * PRIME + ($companyName == null ? 43 : $companyName.hashCode());
    final Object $officeName = this.getOfficeName();
    result = result * PRIME + ($officeName == null ? 43 : $officeName.hashCode());
    final Object $debtPositionStatus = this.getDebtPositionStatus();
    result = result * PRIME + ($debtPositionStatus == null ? 43 : $debtPositionStatus.hashCode());
    final Object $transfer = this.getTransfer();
    result = result * PRIME + ($transfer == null ? 43 : $transfer.hashCode());
    final Object $paymentOptionMetadata = this.getPaymentOptionMetadata();
    result = result * PRIME + ($paymentOptionMetadata == null ? 43 : $paymentOptionMetadata.hashCode());
    final Object $serviceType = this.getServiceType();
    result = result * PRIME + ($serviceType == null ? 43 : $serviceType.hashCode());
    return result;
  }

  public String toString() {
    return "PaymentsModelResponse(nav=" + this.getNav() + ", iuv=" + this.getIuv() + ", organizationFiscalCode=" + this.getOrganizationFiscalCode() + ", amount=" + this.getAmount() + ", description=" + this.getDescription() + ", isPartialPayment=" + this.getIsPartialPayment() + ", payStandIn=" + this.getPayStandIn() + ", dueDate=" + this.getDueDate() + ", retentionDate=" + this.getRetentionDate() + ", paymentDate=" + this.getPaymentDate() + ", reportingDate=" + this.getReportingDate() + ", insertedDate=" + this.getInsertedDate() + ", paymentMethod=" + this.getPaymentMethod() + ", fee=" + this.getFee() + ", pspCompany=" + this.getPspCompany() + ", idReceipt=" + this.getIdReceipt() + ", idFlowReporting=" + this.getIdFlowReporting() + ", status=" + this.getStatus() + ", type=" + this.getType() + ", fiscalCode=" + this.getFiscalCode() + ", streetName=" + this.getStreetName() + ", civicNumber=" + this.getCivicNumber() + ", postalCode=" + this.getPostalCode() + ", city=" + this.getCity() + ", province=" + this.getProvince() + ", region=" + this.getRegion() + ", country=" + this.getCountry() + ", companyName=" + this.getCompanyName() + ", officeName=" + this.getOfficeName() + ", debtPositionStatus=" + this.getDebtPositionStatus() + ", transfer=" + this.getTransfer() + ", paymentOptionMetadata=" + this.getPaymentOptionMetadata() + ", serviceType=" + this.getServiceType() + ")";
  }
}
