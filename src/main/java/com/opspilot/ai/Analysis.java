package com.opspilot.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** What the model returns. The structured-output JSON schema sent to Claude is derived from these records. */
@JsonClassDescription("An investigation of one incident")
public record Analysis(
        @JsonPropertyDescription("What is happening and how bad it is, in two to four sentences, "
                + "for an engineer joining the incident now")
        String summary,
        @JsonPropertyDescription("What the evidence shows, most important first; at most five")
        List<Point> findings,
        @JsonPropertyDescription("What to do next, most useful first; at most five")
        List<Point> nextSteps) {

    public record Point(
            @JsonPropertyDescription("One or two sentences")
            String text,
            @JsonPropertyDescription("References of the items this relies on, such as E3, A1 or P2")
            List<String> sources) {
    }
}
