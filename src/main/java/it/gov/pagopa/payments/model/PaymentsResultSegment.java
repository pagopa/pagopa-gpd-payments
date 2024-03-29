package it.gov.pagopa.payments.model;

import com.microsoft.azure.storage.ResultSegment;
import java.util.List;
import lombok.Data;

@Data
public class PaymentsResultSegment<T> {

  private ResultSegment<T> resultSegment;

  /** Holds the current page number. */
  private int currentPageNumber;

  /** Holds the number of the results. */
  private int length;

  /** Holds the size of the requested page. */
  private Integer pageSize;

  /** Holds the ArrayList of results. */
  private List<T> results;

  /** Holds the information about more elements to recover. */
  private boolean hasMoreResults;
}
