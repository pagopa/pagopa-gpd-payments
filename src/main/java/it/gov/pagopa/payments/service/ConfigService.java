package it.gov.pagopa.payments.service;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;


public class ConfigService {

  @Data
  @Builder
  public static class StationCI {
    private String stationCode;
    private String creditorInstitution;
    private boolean aca;
    private boolean standin;
  }

  @Data
  @Builder
  public static class StationMaintenance {
    private String stationCode;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private boolean standin;
  }

  public static StationMaintenance getStationInMaintenance(String station) {

    // TODO: currently mocked!
    LocalDateTime nowMinusOneHour = LocalDateTime.now().minusHours(1);
    LocalDateTime nowPlusElevenHour = LocalDateTime.now().plusHours(11);
    return StationMaintenance.builder()
            .stationCode(station)
            .startDate(nowMinusOneHour)
            .endDate(nowPlusElevenHour)
            .standin(true)
            .build();
  }

  public static StationCI getCreditorInstitutionStation(String creditorInstitution, String station) {

    // TODO: currently mocked!
    return StationCI.builder()
            .stationCode(station)
            .creditorInstitution(creditorInstitution)
            .aca(true)
            .standin(true)
            .build();
  }
}
