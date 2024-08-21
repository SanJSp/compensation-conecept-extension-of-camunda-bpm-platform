package org.camunda.bpm.engine.delegate;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class NonVitalTaskExecutionListener implements ExecutionListener {
    // constructor with extension property value as parameter
    public NonVitalTaskExecutionListener() {
    }

    private int callCount = 0;

    // notify method is executed when Execution Listener is called
    @Override
    public void notify(DelegateExecution execution) throws Exception {
        if (callCount > 0) {
            int executionCounts = (int) execution.getVariable("executionCounts");
            execution.setVariable("executionCounts", ++executionCounts);
            // detect if task is non-vital
            System.out.println("Hello");
        }
        callCount++;

    }
}
