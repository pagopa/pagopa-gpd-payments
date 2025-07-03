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

    // Private constructor to hide the implicit public one: utility class is a class that only has static members,
    // hence it should not be instantiated.
    private ConfigCacheData() {
        throw new IllegalStateException("Utility class");
    }

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

    private static void setStationMaintenanceMap(ConfigDataV1 configDataV1) {
        // Filter out all stations for which maintenance is over (pruning now > end)
        long now = Instant.now().getEpochSecond();
        try {
            activeStationMaintenanceMap = configDataV1.getMaintenanceStationMap().entrySet().stream()
                    .filter(station -> {
                        String endDateString = String.valueOf(station.getValue().getEndDate());
                        OffsetDateTime endDatetime = OffsetDateTime.parse(endDateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        double endTimestamp = endDatetime.toInstant().getEpochSecond();

                        return endTimestamp > now;
                    })
                    .collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
        } catch (Exception e) {
            log.error("Exception while set active StationMaintenance Map: ", e);
            // reset activeStationMaintenanceMap
            activeStationMaintenanceMap = null;
        }
    }

    private static void setStationCreditorInstitutionMap(ConfigDataV1 configDataV1) {
        stationCreditorInstitutionMap = new ConcurrentHashMap<>();
        try {
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
        } catch (Exception e) {
            log.error("Exception while set StationCreditorInstitution Map: ", e);
            // reset stationCreditorInstitutionMap
            stationCreditorInstitutionMap = null;
        }
    }
}