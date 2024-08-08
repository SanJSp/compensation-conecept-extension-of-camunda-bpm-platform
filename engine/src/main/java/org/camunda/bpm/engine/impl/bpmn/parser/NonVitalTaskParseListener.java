package org.camunda.bpm.engine.impl.bpmn.parser;


import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.NonVitalTaskExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

import java.util.List;

public class NonVitalTaskParseListener extends AbstractBpmnParseListener {

    @Override
    public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {

        // get the <extensionElements ...> element from the user task
        Element extensionElement = userTaskElement.element("extensionElements");
        if (extensionElement != null) {

            // get the <camunda:properties ...> element from the user task
            // TODO adapt this to support camunda:parameter
            Element propertiesElement = extensionElement.element("inputOutput");
            if (propertiesElement != null) {

                //  get list of <camunda:property ...> elements from the service task
                List<Element> propertyList = propertiesElement.elements("inputParameter");
                for (Element property : propertyList) {

                    // get the name and the value of the extension property element
                    String name = property.attribute("name");
                    String value = property.attribute("value");

                    // check if name attribute has the expected value
                    /*if("progress".equals(name)) {

                        // add execution listener to the given service task element
                        // to execute it when the end event of the service task fired
                        ProgressLoggingExecutionListener progressLoggingExecutionListener = new ProgressLoggingExecutionListener(value);
                        activity.addExecutionListener(ExecutionListener.EVENTNAME_END, progressLoggingExecutionListener);
                    }*/
                    NonVitalTaskExecutionListener nonVitalTaskExecutionListener = new NonVitalTaskExecutionListener(value);
                    //activity.addExecutionListener(ExecutionListener.EVENTNAME_START, nonVitalTaskExecutionListener);
                    activity.addExecutionListener(ExecutionListener.EVENTNAME_END, nonVitalTaskExecutionListener);
                }
            }
        }
    }
}
