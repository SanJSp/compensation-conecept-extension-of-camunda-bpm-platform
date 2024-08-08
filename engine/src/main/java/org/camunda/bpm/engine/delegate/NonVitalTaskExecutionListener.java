package org.camunda.bpm.engine.delegate;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class NonVitalTaskExecutionListener implements ExecutionListener {

    private final Logger LOGGER = Logger.getLogger(this.getClass().getName());

    // static value list to see in the UNIT test if the execution listener was executed
    public static List<String> progressValueList = new ArrayList<String>();

    private String propertyValue;

    // constructor with extension property value as parameter
    public NonVitalTaskExecutionListener(String value) {
        this.propertyValue = value;
    }

    // notify method is executed when Execution Listener is called
    @Override
    public void notify(DelegateExecution execution) throws Exception {

        // detect if task is non-vital
        if (execution.getVariable("isVital") != null &&  execution.getVariable("isVital").equals("false")) {
            // is non vital task
            LOGGER.info("this task was marked as non-vital task");
        }
        TaskListener.EVENTNAME_CREATE.equals(execution.getCurrentActivityName());
        progressValueList.add(propertyValue);

        // logging statement to see which value have the property 'progress'

    }
}
