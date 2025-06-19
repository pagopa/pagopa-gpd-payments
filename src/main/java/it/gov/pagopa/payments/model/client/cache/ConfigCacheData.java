package it.gov.pagopa.payments.model.client.cache;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ConfigCacheData {

    @Data
    @Builder
    public static class StationCI {
        private String stationCode;
        private String creditorInstitution;
        private boolean aca;
        private boolean standin;
    }

    @Getter
    @Setter
    private static String version;
    private static Map<String, StationCI> stationCreditorInstitutionMap;
    private static Map<String, MaintenanceStation> activeStationMaintenanceMap;

    public static void setConfigData(ConfigDataV1 configDataV1) {
        try {
            if (configDataV1.getVersion() != null) {
                version = configDataV1.getVersion();
            }
            setStationCreditorInstitutionMap(configDataV1);
            setStationMaintenanceMap(configDataV1);
        } catch (Exception e) {
            log.error("Exception while setConfigCacheData: ", e);
        }
    }

    public static boolean isConfigDataNull() {
        return stationCreditorInstitutionMap == null ||
                activeStationMaintenanceMap == null;
    }

    public static MaintenanceStation getStationInMaintenance(String stationCode) {
        if(activeStationMaintenanceMap == null)
            return null;
        else return activeStationMaintenanceMap.get(stationCode);
    }

    public static StationCI getCreditorInstitutionStation(String creditorInstitution, String station) {
        if(stationCreditorInstitutionMap == null)
            return null;
        else return stationCreditorInstitutionMap.get(creditorInstitution.concat(station));
    }

    private static void setStationCreditorInstitutionMap(ConfigDataV1 configDataV1) {
        // Filters all stations for which maintenance is over
        long now = Instant.now().getEpochSecond();

        activeStationMaintenanceMap = configDataV1.getMaintenanceStationMap().entrySet().stream()
                .filter(station -> {
                    String dateTimeString = String.valueOf(station.getValue().getEndDate());
                    OffsetDateTime odt = OffsetDateTime.parse(dateTimeString, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    double timestamp = odt.toInstant().getEpochSecond();
                    return timestamp > now;
                })
                .collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static void setStationMaintenanceMap(ConfigDataV1 configDataV1) {
        stationCreditorInstitutionMap = new ConcurrentHashMap<>();

        for (StationCreditorInstitution st : configDataV1.getStationCreditorInstitutionMap().values()) {
            String newKey = st.getCreditorInstitutionCode().concat(st.getStationCode());
            StationCI stationCI = StationCI.builder()
                    .stationCode(st.getStationCode())
                    .creditorInstitution(st.getCreditorInstitutionCode())
                    .aca(st.getAca())
                    .standin(st.getStandin())
                    .build();
            stationCreditorInstitutionMap.put(newKey, stationCI);
        }
    }
}