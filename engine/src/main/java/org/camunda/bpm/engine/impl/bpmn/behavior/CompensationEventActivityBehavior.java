/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.impl.bpmn.behavior;

import java.util.List;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.bpmn.helper.CompensationUtil;
import org.camunda.bpm.engine.impl.bpmn.parser.CompensateEventDefinition;
import org.camunda.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;

/**
 * Behavior for a compensation end event.
 *
 * @see IntermediateThrowCompensationEventActivityBehavior
 *
 * @author Philipp Ossler
 *
 */
public class CompensationEventActivityBehavior extends FlowNodeActivityBehavior {

  protected final CompensateEventDefinition compensateEventDefinition;

  public CompensationEventActivityBehavior(CompensateEventDefinition compensateEventDefinition) {
    this.compensateEventDefinition = compensateEventDefinition;
  }

  @Override
  public void execute(ActivityExecution execution) throws Exception {

    final List<EventSubscriptionEntity> eventSubscriptions = collectEventSubscriptions(execution);
    if (eventSubscriptions.isEmpty()) {
      leave(execution);
    } else {
      // async (waitForCompletion=false in bpmn) is not supported
      CompensationUtil.throwCompensationEvent(eventSubscriptions, execution, false);
    }
    if(CompensationUtil.SAVEPOINT_ACTIVITY_ID != null && !CompensationUtil.FLAG_SAVEPOINT_IRRELEVANT){
      RuntimeService runtimeService = execution.getProcessEngineServices().getRuntimeService();
      String processInstanceId = runtimeService.createProcessInstanceQuery().singleResult().getId();
      runtimeService.createModification(execution.getProcessDefinitionId())
              .startAfterActivity(CompensationUtil.SAVEPOINT_ACTIVITY_ID)
              .processInstanceIds(processInstanceId)
              .execute();
      CompensationUtil.SAVEPOINT_ACTIVITY_ID = null;
      CompensationUtil.FLAG_SAVEPOINT_IRRELEVANT = false;
    }
  }


  protected List<EventSubscriptionEntity> collectEventSubscriptions(ActivityExecution execution) {
    final String activityRef = compensateEventDefinition.getActivityRef();
    if (activityRef != null) {
      return CompensationUtil.collectCompensateEventSubscriptionsForActivity(execution, activityRef);
    } else {
      return CompensationUtil.collectCompensateEventSubscriptionsForScope(execution);
    }
  }

  @Override
  public void signal(ActivityExecution execution, String signalName, Object signalData) throws Exception {
    // join compensating executions -
    // only wait for non-event-scope executions cause a compensation event subprocess consume the compensation event and
    // do not have to compensate embedded subprocesses (which are still non-event-scope executions)

    if (((PvmExecutionImpl) execution).getNonEventScopeExecutions().isEmpty()) {
      leave(execution);
    } else {
      ((ExecutionEntity) execution).forceUpdate();
    }
  }

}