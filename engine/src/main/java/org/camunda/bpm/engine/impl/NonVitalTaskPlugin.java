package org.camunda.bpm.engine.impl;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.DefaultFailedJobParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.NonVitalTaskParseListener;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.parser.HistoryParseListener;
import org.camunda.bpm.engine.impl.history.producer.CacheAwareHistoryEventProducer;
import org.camunda.bpm.engine.impl.history.producer.HistoryEventProducer;

import java.util.ArrayList;
import java.util.List;

public class NonVitalTaskPlugin extends AbstractProcessEnginePlugin {
    @Override
    public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
        // get all existing preParseListeners
        List<BpmnParseListener> preParseListeners = processEngineConfiguration.getCustomPreBPMNParseListeners();

        if(preParseListeners == null) {
            // if no preParseListener exists, create new list
            preParseListeners = new ArrayList<BpmnParseListener>();
            processEngineConfiguration.setCustomPreBPMNParseListeners(preParseListeners);
        }

        preParseListeners.add(new NonVitalTaskParseListener());
    }



}
