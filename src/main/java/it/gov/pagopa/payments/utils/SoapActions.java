package it.gov.pagopa.payments.utils;

/**
 * Supported SOAP action names, shared across the endpoint, the dispatcher and the WSDL definition.
 */
public final class SoapActions {

  private SoapActions() {
    // utility class
  }

  public static final String PA_VERIFY_PAYMENT_NOTICE = "paVerifyPaymentNotice";
  public static final String PA_GET_PAYMENT = "paGetPayment";
  public static final String PA_GET_PAYMENT_V2 = "paGetPaymentV2";
  public static final String PA_SEND_RT = "paSendRT";
  public static final String PA_SEND_RT_V2 = "paSendRTV2";
  public static final String PA_DEMAND_PAYMENT_NOTICE = "paDemandPaymentNotice";
}
