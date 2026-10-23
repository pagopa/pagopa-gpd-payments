package it.gov.pagopa.payments.model.client.cache;

import it.gov.pagopa.payments.logging.TechnicalFailureLogging;
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

    /**
     * Rebuilds both maps, returning false if any rebuild failure was absorbed.
     */
    public static boolean setConfigData(ConfigDataV1 configDataV1) {
        try {
            if (configDataV1.getVersion() != null) {
                version = configDataV1.getVersion();
            }
            boolean stationsUpdated = setStationCreditorInstitutionMap(configDataV1);
            boolean maintenanceUpdated = setStationMaintenanceMap(configDataV1);
            return stationsUpdated && maintenanceUpdated;
        } catch (Exception e) {
            TechnicalFailureLogging.logTechnicalFailure(log, "Config cache data rebuild failed", e);
            return false;
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

    private static boolean setStationMaintenanceMap(ConfigDataV1 configDataV1) {
        // Filter out all stations for which maintenance is over (pruning now > end)
        long now = Instant.now().getEpochSecond();
        try {
            activeStationMaintenanceMap = configDataV1.getMaintenanceStationMap().entrySet().stream()
                    .filter(station -> {
                        String endDateString = String.valueOf(station.getValue().getEndDate());
                        OffsetDateTime endDatetime = OffsetDateTime.parse(endDateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        long endTimestamp = endDatetime.toInstant().getEpochSecond();

                        return endTimestamp > now;
                    })
                    .collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
            return true;
        } catch (Exception e) {
            TechnicalFailureLogging.logTechnicalFailure(log, "Config cache maintenance map rebuild failed", e);
            // reset activeStationMaintenanceMap
            activeStationMaintenanceMap = null;
            return false;
        }
    }

    private static boolean setStationCreditorInstitutionMap(ConfigDataV1 configDataV1) {
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
            return true;
        } catch (Exception e) {
            TechnicalFailureLogging.logTechnicalFailure(log, "Config cache creditor institution map rebuild failed", e);
            // reset stationCreditorInstitutionMap
            stationCreditorInstitutionMap = null;
            return false;
        }
    }
}