Feature: All about Send Receipt Phase on Debt Position Payments workflow

  Background:
    Given Payments running
    And GPD running
    And ApiConfig running
    And the creditor institution "77777777777"
    And the creditor institution broker "15376371009"
    And the station "15376371009_01" for the broker "15376371009"
    And if necessary, refresh the configuration and wait 30 seconds
    And a valid debt position
    And a proper verification of debt position
    And a proper activation of debt position

#  This test was annotated because it is assumed that this check on the fiscal code is done by the NDP
#  @GPDScenario
#  Scenario: Activate phase - Fail (no valid fiscal code)
#    Given an invalid fiscal code
#    When the client sends the SendPaymentOutcomeRequest to Nodo
#    And the client sends the SendRTRequest
#    Then the client receives status code 200
#    And the client receives a KO with the "PAA_ID_DOMINIO_ERRATO" fault code error

  @GPDScenario
  Scenario: Activate phase - Fail (request a receipt for a debt position that is not in PAID status)
    Given an invalid fiscal code
    And the client sends the SendRTRequest
    Then the client receives status code 200
    And the client receives a KO with the "PAA_PAGAMENTO_SCONOSCIUTO" fault code error

  @GPDScenario
  Scenario: Activate phase - Success
    Given a valid fiscal code
    When the client sends the SendPaymentOutcomeRequest to Nodo
    And the client sends the SendRTRequest
    Then the client receives status code 200
    And the client receives an OK in the response

  @GPDScenario
  Scenario: Activate phase V2 - Success
    Given a valid fiscal code
    When the client sends the SendPaymentOutcomeRequest to Nodo
    And the client sends the SendRTV2Request
    Then the client receives status code 200
    And the client receives an OK in the response
