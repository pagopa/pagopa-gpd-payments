Feature: All about Activate Phase on Debt Position Payments workflow

  Background:
    Given Payments running
    And GPD running
    And ApiConfig running
    And the creditor institution "00168480242"
    And the creditor institution broker "80007580279"
    And the station "80007580279_01"
    And a valid debt position

  @GPDScenario
  Scenario: Get Payment phase - Success
    Given a valid fiscal code
    When the client sends the GetPaymentRequest
    Then the client receives status code 200
    And the client retrieves the payment amount "300.00" in the response
    And the client retrieves the IBANs in the response
    
  #@GPDScenario
  #Scenario: Get Payment V2 phase - Success
  #  Given a valid fiscal code
  #  When the client sends the GetPaymentV2Request
  #  Then the client receives status code 200
  #  And the client retrieves the amount "300.00" in the response