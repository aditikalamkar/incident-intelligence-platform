package com.opspilot.ai;

/** The model behind the assistant. */
public interface Investigator {

    /** @param model the model that wrote the analysis, which differs from {@link #model()} after a fallback */
    record Answer(Analysis analysis, String model, long inputTokens, long outputTokens) {
    }

    /** The model investigations are sent to. */
    String model();

    /** @throws InvestigationFailedException with a message for the user, when there is no analysis */
    Answer investigate(Briefing briefing);
}
