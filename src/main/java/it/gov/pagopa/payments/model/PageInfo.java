package it.gov.pagopa.payments.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageInfo {

  @JsonProperty("receipts_list")
  @Schema(required = true)
  @NotNull
  @Valid
  private List<ReceiptModelResponse> receiptsList;

  @JsonProperty("total_pages")
  @Schema(description = "More elements on the following pages", required = true)
  @PositiveOrZero
  Integer totalPages;
}
