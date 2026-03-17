Feature: Get Payment phase on Debt Position Payments workflow

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
  Scenario: Get Payment phase - Success
    Given a valid fiscal code
    When the client sends the GetPaymentRequest
    Then the client receives status code 200
    And the client retrieves the payment amount "300.00" in the response
    And the client retrieves the IBANs in the response
    
  @GPDScenario
  Scenario: Get Payment V2 phase - Success
    Given a valid fiscal code
    When the client sends the GetPaymentV2Request
    Then the client receives status code 200
    And the client retrieves the payment amount "300.00" in the response
    And the client retrieves the IBANs in the response