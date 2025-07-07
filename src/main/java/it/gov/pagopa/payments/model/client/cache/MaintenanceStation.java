package it.gov.pagopa.payments.model.client.cache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.io.Serializable;
import java.time.OffsetDateTime;

/** MaintenanceStation */
@EqualsAndHashCode
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class MaintenanceStation implements Serializable {
    @JsonProperty(value = "station_code", required = true)
    private String stationCode;

    @JsonProperty(value = "start_date", required = true)
    private OffsetDateTime startDate;

    @JsonProperty(value = "end_date")
    private OffsetDateTime endDate;

    @JsonProperty(value = "standin", required = true)
    private Boolean isStandin;

    @JsonIgnore
    public String getIdentifier() {
        return stationCode;
    }
}
