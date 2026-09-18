# ============================================================
# Feature: Offline AI Assistant
# Phase: 14.11 — BDD Scenarios
# ============================================================

Feature: Offline AI Assistant

  Background:
    Given the user is authenticated
    And the app has previously loaded conversations

  Scenario: User opens previous conversation while offline
    Given the user has a cached conversation titled "Kotlin Coroutines"
    And the device network is offline
    When the user opens the conversation list
    Then the cached conversation "Kotlin Coroutines" should be displayed
    And the connectivity status bar should show "You're offline"
    And the cache status indicator should show the last sync time

  Scenario: User attempts to send a Cloud AI message while offline
    Given the device network is offline
    And the current AI mode is "Cloud AI"
    When the user opens a new chat
    Then the message input bar should be disabled
    And a tooltip should indicate "No internet connection — switch to Gemma to continue"

  Scenario: User switches to Gemma while offline and sends a message
    Given the device network is offline
    And on-device Gemma is installed and Ready
    When the user changes the AI mode to "Gemma"
    And the user types "Summarize clean architecture"
    And the user taps the Send button
    Then the AI response should be generated on-device
    And the AI mode indicator should display "Gemma"
    And no network request should be made

  Scenario: Device comes back online after being offline
    Given the device was previously offline
    And the user is on the conversation list screen
    When the device network becomes available
    Then the connectivity status bar should briefly show "Back online"
    And the status bar should automatically dismiss after 3 seconds
    And a background sync should begin

  Scenario: Offline empty state shown when no cached conversations
    Given the device network is offline
    And the user has no cached conversations
    When the user opens the conversation list
    Then an offline empty state should be displayed
    And a "Use Gemma" call-to-action should be visible if Gemma is Ready
