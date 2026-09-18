# ============================================================
# Feature: RAG Sources Display
# Phase: 14.11 — BDD Scenarios
# ============================================================

Feature: RAG Sources Display

  Background:
    Given the user is authenticated
    And the user has uploaded at least one indexed document

  Scenario: RAG response shows source chip
    Given the user is in a document chat session
    When the AI generates a RAG response
    Then a "Sources" chip should appear below the response
    And the chip should display the count of source documents

  Scenario: User taps Sources chip to view citations
    Given a RAG response with 3 source documents is displayed
    When the user taps the "3 sources" chip
    Then a bottom sheet should appear
    And the bottom sheet should list all 3 source citations
    And each citation should show the document name and reference

  Scenario: Sources bottom sheet displays correct document names
    Given a RAG response sourced from "Android Architecture.pdf" and "API Guide.md"
    When the user opens the sources bottom sheet
    Then "Android Architecture.pdf" should be listed
    And "API Guide.md" should be listed

  Scenario: Document upload shows indexing progress
    Given the user is on the Documents screen
    When the user uploads a new PDF document
    Then the document should immediately appear with "Uploading" status
    And the status should transition to "Processing"
    And eventually reach "Indexed" status

  Scenario: Failed document shows retry option
    Given a document with "Failed" indexing status
    When the user views the document list
    Then a retry action should be visible for the failed document

  Scenario: AI mode indicator shows RAG for document-grounded responses
    Given the user is in a document chat session
    When the AI generates a response citing documents
    Then the AI mode indicator above the response should show "RAG"
    And the indicator color should be distinct from Cloud AI and Gemma colors
