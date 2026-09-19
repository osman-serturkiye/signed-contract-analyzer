package com.signedcontract.model;

/**
 * Checked exception thrown when a critical error occurs during the
 * ContractAnalyzer pipeline execution.
 */
public class ContractAnalysisException extends Exception {

    public ContractAnalysisException(String message) {
        super(message);
    }

    public ContractAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
