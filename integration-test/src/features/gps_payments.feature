Feature: All about Spontaneous Payments

  Background:
    Given Payments running
    And Vertical service running
    And GPD running
    And ApiConfig running

  @GPSScenario
  Scenario: call vertical service for e.bollo
    Given the creditor institution "77777777777" enrolled to the vertical service "00005"
    When the client sends the paDemandPaymentNoticeRequest
    Then the client receives status code 200
    And the client retrieves the amount "16.00" in the response

  @GPSScenario
  Scenario: call vertical service without amount
    Given the creditor institution "77777777777" enrolled to the vertical service "00005"
    When the client sends a wrong paDemandPaymentNoticeRequest
    Then the client receives status code 200
    And the client receives a KO in the response
