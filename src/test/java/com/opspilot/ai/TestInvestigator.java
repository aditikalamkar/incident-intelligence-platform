package com.opspilot.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Stands in for Claude in integration tests: records each briefing and answers as the test scripted. */
public class TestInvestigator implements Investigator {

    public static final String MODEL = "test-model";

    private final List<Briefing> briefings = new CopyOnWriteArrayList<>();
    private volatile Function<Briefing, Answer> behaviour = TestInvestigator::summary;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public Answer investigate(Briefing briefing) {
        briefings.add(briefing);
        return behaviour.apply(briefing);
    }

    public void answerWith(Function<Briefing, Answer> behaviour) {
        this.behaviour = behaviour;
    }

    public void reset() {
        briefings.clear();
        behaviour = TestInvestigator::summary;
    }

    public List<Briefing> briefings() {
        return briefings;
    }

    public static Answer answer(Analysis analysis) {
        return new Answer(analysis, MODEL, 1200, 300);
    }

    private static Answer summary(Briefing briefing) {
        return answer(new Analysis("Nothing unusual.", List.of(), List.of()));
    }
}
