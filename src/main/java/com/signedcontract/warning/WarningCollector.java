package com.signedcontract.warning;

import com.signedcontract.model.AnalysisWarning;
import com.signedcontract.model.Severity;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe accumulator for non-critical pipeline warnings.
 *
 * <p>Validates: Requirements 13.1–13.4, 24.1–24.4</p>
 */
public class WarningCollector {

    private final List<AnalysisWarning> warnings = new CopyOnWriteArrayList<>();

    public void addWarning(String component, Severity severity, String message) {
        warnings.add(new AnalysisWarning(component, severity, message));
    }

    /** Returns an immutable snapshot of the warnings collected so far. */
    public List<AnalysisWarning> getWarnings() {
        return Collections.unmodifiableList(List.copyOf(warnings));
    }

    public void clear() {
        warnings.clear();
    }
}
