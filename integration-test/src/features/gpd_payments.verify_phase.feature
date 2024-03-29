Feature: All about Verify Phase on Debt Position Payments workflow

  Background:
    Given Payments running
    And GPD running
    And ApiConfig running
    And the creditor institution "77777777777"
    And the creditor institution broker "15376371009"
    And the station "15376371009_01" for the broker "15376371009"
    And if necessary, refresh the configuration and wait 30 seconds
    And a valid debt position

  @GPDScenario
  Scenario: Verify phase - Fail (no valid fiscal code)
    Given an invalid fiscal code
    When the client sends the VerifyPaymentNoticeRequest
    Then the client receives status code 200
    And the client receives a KO with the "PAA_PAGAMENTO_SCONOSCIUTO" fault code error

  @GPDScenario
  Scenario: Verify phase - Success
    Given a valid fiscal code
    When the client sends the VerifyPaymentNoticeRequest
    Then the client receives status code 200
    And the client retrieves the amount "300.00" in the response