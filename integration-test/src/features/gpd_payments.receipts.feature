@GPDScenario
Feature: Payments receipts REST API

  Scenario: Get organization receipts - success
    Given Payments running
    And GPD running
    And ApiConfig running
    And the configured creditor institution
    And the configured creditor institution broker
    And the configured station
    And if necessary, refresh the configuration and wait 30 seconds
    And a valid debt position
    And a proper verification of debt position
    And a proper activation of debt position
    When the client sends the SendPaymentOutcomeRequest to Nodo
    And the client sends the SendRTRequest
    And the client sends the GetOrganizationReceiptsRequest
    Then the client receives status code 200
    And the receipts response contains results
    
  Scenario: Get organization receipts filtered by IUV - success
    Given Payments running
    And GPD running
    And ApiConfig running
    And the configured creditor institution
    And the configured creditor institution broker
    And the configured station
    And if necessary, refresh the configuration and wait 30 seconds
    And a valid debt position
    And a proper verification of debt position
    And a proper activation of debt position
    When the client sends the SendPaymentOutcomeRequest to Nodo
    And the client sends the SendRTRequest
    And the client sends the GetOrganizationReceipts filtered by IUV request
    Then the client receives status code 200
    And the receipts response is valid