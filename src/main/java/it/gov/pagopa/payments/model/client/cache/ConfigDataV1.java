package it.gov.pagopa.payments.model.client.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * ConfigDataV1
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigDataV1 {

    @JsonProperty("version")
    @Builder.Default
    private String version = null;

    @JsonProperty("creditorInstitutionStations")
    @Valid
    private Map<String, StationCreditorInstitution> stationCreditorInstitutionMap = new HashMap<>();

    @JsonProperty("maintenanceStations")
    @Valid
    private Map<String, MaintenanceStation> maintenanceStationMap = new HashMap<>();
}