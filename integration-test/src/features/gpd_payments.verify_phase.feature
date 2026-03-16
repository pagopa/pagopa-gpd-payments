Feature: All about Verify Phase on Debt Position Payments workflow

  Background:
    Given Payments running
    And GPD running
    And ApiConfig running
    And the configured creditor institution
    And the configured creditor institution broker
    And the configured station
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