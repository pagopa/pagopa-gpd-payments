package it.gov.pagopa.payments.model.client.cache;

import lombok.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigCacheData {

    @Data
    @Builder
    public static class StationCI {
        private String stationCode;
        private String creditorInstitution;
        private boolean aca;
        private boolean standin;
    }

    private String cacheVersion;
    private String version;
    private Map<String, StationCI> stationCreditorInstitutionMap;
    private Map<String, MaintenanceStation> activeStationMaintenanceMap;

    public void set(ConfigDataV1 configDataV1) {
        try {
            this.setStationCreditorInstitutionMap(configDataV1);
            this.setStationMaintenanceMap(configDataV1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isNull() {
        return this.stationCreditorInstitutionMap == null ||
                this.activeStationMaintenanceMap == null;
    }

    public MaintenanceStation getStationInMaintenance(String stationCode) {
        return this.activeStationMaintenanceMap.get(stationCode);
    }

    public StationCI getCreditorInstitutionStation(String creditorInstitution, String station) {
        return this.stationCreditorInstitutionMap.get(creditorInstitution.concat(station));
    }

    private void setStationCreditorInstitutionMap(ConfigDataV1 configDataV1) {
        // Filters all stations for which maintenance is over
        long now = Instant.now().getEpochSecond();

        this.activeStationMaintenanceMap = configDataV1.getMaintenanceStationMap().entrySet().stream()
                .filter(station -> {
                    String dateTimeString = String.valueOf(station.getValue().getEndDate());
                    OffsetDateTime odt = OffsetDateTime.parse(dateTimeString, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    double timestamp = odt.toInstant().getEpochSecond();
                    return timestamp > now;
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void setStationMaintenanceMap(ConfigDataV1 configDataV1) {
        this.stationCreditorInstitutionMap = new HashMap<>();

        for (StationCreditorInstitution st : configDataV1.getStationCreditorInstitutionMap().values()) {
            String newKey = st.getCreditorInstitutionCode().concat(st.getStationCode());
            StationCI stationCI = StationCI.builder()
                    .stationCode(st.getStationCode())
                    .creditorInstitution(st.getCreditorInstitutionCode())
                    .aca(st.getAca())
                    .standin(st.getStandin())
                    .build();
            this.stationCreditorInstitutionMap.put(newKey, stationCI);
        }
    }
}