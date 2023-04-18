package it.gov.pagopa.payments.model;

import lombok.Data;

import java.util.List;

@Data
public class PaymentsResult<T> {

  /** Holds the current page number. */
  private int currentPageNumber;

  /** Holds the number of the results. */
  private int length;

  /** Holds the ArrayList of results. */
  private List<T> results;

}
