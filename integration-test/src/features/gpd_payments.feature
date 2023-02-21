Feature: All about Debt Position Payments

  Background:
    Given Payments running
    And GPD running
    And ApiConfig running
    And the creditor institution "77777777777"
    And the creditor institution broker "77777777777"
    And a valid debt position

  @GPDScenario
  Scenario: Verify phase - Fail (no valid station)
    Given the station "77777777777_99" not related to creditor institution
    When the client sends the VerifyPaymentNoticeRequest
    Then the client receives status code 200
    And the client receives a KO with the "PAA_ID_DOMINIO_ERRATO" fault code error

  @GPDScenario
  Scenario: Verify phase - Success
    Given the station "77777777777_99" related to creditor institution
    When the client sends the VerifyPaymentNoticeRequest
    Then the client receives status code 200
    And the client retrieves the amount "350.00" in the response

  @GPDScenario
  Scenario: Activate phase - Fail (no valid station)
    Given the station "77777777777_99" not related to creditor institution
    When the client sends the GetPaymentRequest
    Then the client receives status code 200
    And the client receives a KO with the "PAA_ID_DOMINIO_ERRATO" fault code error

  @GPDScenario
  Scenario: Activate phase - Success
    Given the station "77777777777_99" related to creditor institution
    When the client sends the GetPaymentRequest
    Then the client receives status code 200
    And the client receives an OK in the response

  @GPDScenario
  Scenario: Send receipt phase - Fail (no valid station)
    Given the station "77777777777_99" not related to creditor institution
    When the client sends the SendRTRequest
    Then the client receives status code 200
    And the client receives a KO with the "PAA_ID_DOMINIO_ERRATO" fault code error

  @GPDScenario
  Scenario: Send receipt phase - Success
    Given the station "77777777777_99" related to creditor institution
    When the client sends the SendRTRequest
    Then the client receives status code 200
    And the client receives an OK in the response

