package it.gov.pagopa.payments.model.client.cache;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.io.Serializable;

@EqualsAndHashCode
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationCreditorInstitution implements Serializable {

    @JsonProperty(value = "creditor_institution_code", required = true)
    private String creditorInstitutionCode;

    @JsonProperty(value = "station_code", required = true)
    private String stationCode;

    @JsonProperty(value = "application_code")
    private Long applicationCode;

    @JsonProperty(value = "aux_digit")
    private Long auxDigit;

    @JsonProperty(value = "segregation_code")
    private Long segregationCode;

    @JsonProperty(value = "mod4", required = true)
    private Boolean mod4;

    @JsonProperty(value = "broadcast", required = true)
    private Boolean broadcast;

    @JsonProperty(value = "primitive_version", required = true)
    private Integer primitiveVersion;

    @JsonProperty(value = "spontaneous_payment", required = true)
    private Boolean spontaneousPayment;

    @JsonProperty(value = "aca", required = true)
    private Boolean aca;

    @JsonProperty(value = "standin", required = true)
    private Boolean standin;

    @JsonIgnore
    public String getIdentifier() {
        return stationCode
                + "_"
                + creditorInstitutionCode
                + "_"
                + auxDigit
                + "_"
                + applicationCode
                + "_"
                + segregationCode;
    }
}
