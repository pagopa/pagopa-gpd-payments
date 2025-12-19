package it.gov.pagopa.payments.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class PaymentsResult {

  /** Holds the current page number. */
  private int currentPageNumber;

  /** Holds the number of the results. */
  private int length;

  /** Holds the number of the pages. */
  private int totalPages;

  /** Holds the ArrayList of receipts. */
  @JsonProperty("receipts_list")
  private List<ReceiptModelResponse> receiptsList;

}
