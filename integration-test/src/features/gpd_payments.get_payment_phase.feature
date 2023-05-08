Feature: All about Activate Phase on Debt Position Payments workflow

  Background:
    Given Payments running
    And GPD running
    And ApiConfig running
    And the creditor institution "77777777777"
    And the creditor institution broker "15376371009"
    And the station "15376371009_01"
    And a valid debt position

  @GPDScenario
  Scenario: Get Payment phase - Success
    Given a valid fiscal code
    When the client sends the GetPaymentRequest
    Then the client receives status code 200
    And the client retrieves the amount "300.00" in the response
    
  @GPDScenario
  Scenario: Get Payment V2 phase - Success
    Given a valid fiscal code
    When the client sends the GetPaymentV2Request
    Then the client receives status code 200
    And the client retrieves the amount "300.00" in the response