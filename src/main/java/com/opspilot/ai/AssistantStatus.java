package com.opspilot.ai;

/** @param model the model investigations are sent to; null when the assistant is off */
public record AssistantStatus(boolean enabled, String model) {
}
