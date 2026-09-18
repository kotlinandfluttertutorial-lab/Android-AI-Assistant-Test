# ============================================================
# Feature: Gemma Model Switching
# Phase: 14.11 — BDD Scenarios
# ============================================================

Feature: Gemma Model Switching

  Background:
    Given the user is authenticated

  Scenario: Model status card shows Ready when Gemma is installed
    Given Gemma is installed and verified on the device
    When the user navigates to Model Settings
    Then the ModelStatusCard should show "Ready" status
    And the storage size should be displayed
    And the "Manage" button should be visible

  Scenario: Model status card shows Download option when not installed
    Given Gemma is not installed on the device
    When the user navigates to Model Settings
    Then the ModelStatusCard should show "Not installed" status
    And a "Download" button should be visible

  Scenario: Model download progress is shown during download
    Given Gemma is not installed
    When the user taps "Download" on the ModelStatusCard
    Then the status should change to "Downloading"
    And a download progress indicator should appear
    And the download percentage should be displayed

  Scenario: Switching AI mode to Gemma changes the mode indicator
    Given Gemma is installed and Ready
    When the user changes the AI mode to "Gemma" in Settings
    And the user opens a new chat
    Then the TopAppBar should show the Gemma AI mode indicator
    And the AI mode indicator should use the teal/emerald color scheme

  Scenario: Switching from Gemma to Cloud AI changes the indicator
    Given the current AI mode is "Gemma"
    When the user changes the AI mode to "Cloud AI" in Settings
    And the user sends a new message
    Then the AI mode indicator above the response should show "Cloud AI"
    And the indicator color should be blue

  Scenario: Gemma model loading indicator appears during initialization
    Given Gemma is installed but not yet loaded into memory
    When the user opens a new chat with Gemma mode selected
    Then the chat TopAppBar should show a "Loading model…" indicator
    And the message input bar should be disabled during loading
    And the indicator should disappear once the model is Ready

  Scenario: On-device Gemma indicator shown in Home Dashboard
    Given Gemma is installed and Ready
    When the user opens the Home Dashboard
    Then an "On-device Gemma" chip should be visible in the AI mode status row
    And the chip should use the Gemma teal color scheme
