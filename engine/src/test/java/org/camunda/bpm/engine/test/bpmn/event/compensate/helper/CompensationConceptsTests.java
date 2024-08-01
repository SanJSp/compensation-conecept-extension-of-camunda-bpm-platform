package org.camunda.bpm.engine.test.bpmn.event.compensate.helper;

import org.assertj.core.api.Assertions;
import org.camunda.bpm.engine.filter.Filter;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.impl.TaskQueryImpl;
import org.camunda.bpm.engine.impl.TaskQueryVariableValue;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.mock.Mocks;
import org.camunda.bpm.engine.test.util.PluggableProcessEngineTest;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputOutput;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputParameter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class CompensationConceptsTests extends PluggableProcessEngineTest {

    protected String deploymentId;

    protected CamundaInputParameter findInputParameterByName(BaseElement baseElement, String name) {
        Collection<CamundaInputParameter> camundaInputParameters = baseElement.getExtensionElements().getElementsQuery().filterByType(CamundaInputOutput.class).singleResult().getCamundaInputParameters();
        for (CamundaInputParameter camundaInputParameter : camundaInputParameters) {
            if (camundaInputParameter.getCamundaName().equals(name)) {
                return camundaInputParameter;
            }
        }
        throw new BpmnModelException("Unable to find camunda:inputParameter with name '" + name + "' for element with id '" + baseElement.getId() + "'");
    }

    @After
    public void clear() {
        Mocks.reset();

        if (deploymentId != null) {
            repositoryService.deleteDeployment(deploymentId, true);
            deploymentId = null;
        }
    }


    private void completeTasks(String taskName, int times) {
        List<org.camunda.bpm.engine.task.Task> tasks = taskService.createTaskQuery().taskName(taskName).list();

        assertTrue("Actual there are " + tasks.size() + " open tasks with name '" + taskName + "'. Expected at least " + times, times <= tasks.size());

        Iterator<org.camunda.bpm.engine.task.Task> taskIterator = tasks.iterator();
        for (int i = 0; i < times; i++) {
            Task task = taskIterator.next();
            taskService.complete(task.getId());
        }
    }

    private void completeTask(String taskName) {
        completeTasks(taskName, 1);
    }


    @Test
    public void test123123() {
        BpmnModelInstance modelInstance = Bpmn.createExecutableProcess("foo").startEvent("start").userTask("userTask").camundaInputParameter("var", "Hello World${'!'}").endEvent("end").done();

        testRule.deploy(modelInstance);
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("foo");

        VariableInstance variableInstance = runtimeService.createVariableInstanceQuery().variableName("var").singleResult();

        // then
        assertEquals("Hello World!", variableInstance.getValue());
        UserTask serviceTask = modelInstance.getModelElementById("userTask");

        CamundaInputParameter inputParameter = findInputParameterByName(serviceTask, "var");
        Assertions.assertThat(inputParameter.getCamundaName()).isEqualTo("var");


    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.nonVitalTaskTest.bpmn20.xml")
    @Test
    public void nonVitalTaskTest() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();

        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        completeTask("Book Flight");
        bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("collectPoints").singleResult().getTaskState());

        List<HistoricActivityInstance> historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(3, historicActivityInstance.size()); // start Event, bookFlight, collectPoints

        Task collectPointsTask = taskService.createTaskQuery().taskName("Collect Royality Points").singleResult();
        Map<String, Object> variables = taskService.getVariables(collectPointsTask.getId());

        assertEquals(1, variables.size());
        assertEquals("false", variables.get("isVital"));

        taskService.handleBpmnError(collectPointsTask.getId(), "errorCode");
        String state = ((TaskEntity) collectPointsTask).getTaskState();
        System.out.println(state);

        // here should the non-vital magic happen
        // TODO add extension element to task that should be non-vital
        // TODO implement non vital task behaviour

        collectPointsTask = taskService.createTaskQuery().taskName("Collect Royality Points").singleResult();
        assertNull(collectPointsTask);

        // TODO this should pass
        // assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());
        // maybe need to build a lifecycle listener
    }



    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.sandroTest.bpmn20.xml")
    @Test
    public void sandroTest() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Book Hotel");
        completeTask("Book Car");
        completeTask("Pay Booking");

        testRule.assertProcessEnded(processInstanceId);

        List<HistoricActivityInstance> historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(6, historicActivityInstance.size());

        assertEquals("startEvent", historicActivityInstance.get(0).getActivityId());
        assertEquals("bookFlight", historicActivityInstance.get(1).getActivityId());
        assertEquals("bookHotel", historicActivityInstance.get(2).getActivityId());
        assertEquals("bookCar", historicActivityInstance.get(3).getActivityId());
        assertEquals("payBooking", historicActivityInstance.get(4).getActivityId());
        assertEquals("endEvent", historicActivityInstance.get(5).getActivityId());

        processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();
        completeTask("Book Flight");
        completeTask("Book Hotel");
        completeTask("Book Car");

        Task task = taskService.createTaskQuery().taskName("Pay Booking").singleResult();
        Assertions.assertThat(task).isNotNull();
        Assertions.assertThat(task.getName()).isEqualTo("Pay Booking");
        taskService.handleBpmnError(task.getId(), "errorCode");
        historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(16, historicActivityInstance.size());

        assertEquals("startEvent", historicActivityInstance.get(6).getActivityId());
        assertEquals("bookFlight", historicActivityInstance.get(7).getActivityId());
        assertEquals("bookHotel", historicActivityInstance.get(8).getActivityId());
        assertEquals("bookCar", historicActivityInstance.get(9).getActivityId());
        assertEquals("payBooking", historicActivityInstance.get(10).getActivityId());

        // following have same start times, leading to flaky test if position is tested
        // assertEquals("errorEvent", historicActivityInstance.get(11).getActivityId());
        //assertEquals("compensationThrowEndEvent", historicActivityInstance.get(12).getActivityId());

        assertEquals("cancelCar", historicActivityInstance.get(13).getActivityId());
        assertEquals("cancelHotel", historicActivityInstance.get(14).getActivityId());
        assertEquals("cancelFlight", historicActivityInstance.get(15).getActivityId());
        testRule.assertProcessNotEnded(processInstanceId);

        completeTask("Cancel Flight");
        completeTask("Cancel Hotel");
        completeTask("Cancel Car");
        testRule.assertProcessEnded(processInstanceId);


        processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();
        historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        // again same start time as startEvent, hence flaky
        //assertEquals("bookFlight", historicActivityInstance.get(17).getActivityId());
        completeTask("Book Flight");
        historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals("bookHotel", historicActivityInstance.get(18).getActivityId());
        assertEquals(null, historicActivityInstance.get(18).getEndTime());
        completeTask("Book Hotel");
        historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals("bookCar", historicActivityInstance.get(19).getActivityId());
        assertEquals(null, historicActivityInstance.get(19).getEndTime());
       // testRule.assertProcessEnded(processInstanceId);

        // learning -> Every task is started as soon as enable, but as it's a user task I have to complete it manually.
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        assertEquals(false, processInstance.isEnded());
        assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("bookCar").singleResult().getTaskState());
        completeTask("Book Car");
        List<HistoricActivityInstance> completedBookCarTasks = historyService.createHistoricActivityInstanceQuery().activityName("Book Car").list();
        assertNotNull(completedBookCarTasks.get(completedBookCarTasks.size() - 1).getEndTime());


        // TODO kann ich auch executen, ohne alles einzeln zu triggern? Was wenn es keine user tasks sind?

    }
}
