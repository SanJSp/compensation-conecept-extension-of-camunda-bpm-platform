package org.camunda.bpm.engine.test.bpmn.event.compensate.helper;

import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.util.PluggableProcessEngineTest;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputOutput;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaInputParameter;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.*;

public class CompensationConceptsTests extends PluggableProcessEngineTest {

    protected CamundaInputParameter findInputParameterByName(BaseElement baseElement, String name) {
        Collection<CamundaInputParameter> camundaInputParameters = baseElement.getExtensionElements().getElementsQuery().filterByType(CamundaInputOutput.class).singleResult().getCamundaInputParameters();
        for (CamundaInputParameter camundaInputParameter : camundaInputParameters) {
            if (camundaInputParameter.getCamundaName().equals(name)) {
                return camundaInputParameter;
            }
        }
        throw new BpmnModelException("Unable to find camunda:inputParameter with name '" + name + "' for element with id '" + baseElement.getId() + "'");
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

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.simpleCompensationTest.bpmn20.xml")
    @Test
    public void simpleCompensationTest() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();
        completeTask("Book Flight");
        HistoricActivityInstance cancelFlightTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Cancel Flight").singleResult();
        assertNull(cancelFlightTaskHistory);
    }


    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.savepointTest.bpmn20.xml")
    @Test
    public void savepointTest() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Book Hotel");
        completeTask("Book Car");

        Task paymentTask = taskService.createTaskQuery().taskName("Pay Booking").singleResult();
        taskService.handleBpmnError(paymentTask.getId(), "errorCode");

        HistoricActivityInstance cancelCarTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(cancelCarTaskHistory);
        assertNotNull(cancelCarTaskHistory.getStartTime());
        assertNull(cancelCarTaskHistory.getEndTime());

        completeTask("Cancel Car");

        cancelCarTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(cancelCarTaskHistory.getEndTime());


        HistoricActivityInstance cancelHotelTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Cancel Hotel").singleResult();
        assertNull(cancelHotelTaskHistory);

        HistoricActivityInstance cancelFlightTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Cancel Flight").singleResult();
        assertNull(cancelFlightTaskHistory);


        //assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("Book Car").singleResult().getTaskState());

        testRule.assertProcessEnded(processInstanceId);
    }



    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.nonVitalTaskTest.bpmn20.xml")
    @Test
    public void nonVitalTaskTest() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();

        completeTask("Book Flight");

        // check if task is completed
        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        // check if non vital task is activated
        assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("collectPoints").singleResult().getTaskState());

        // check if start event, bookFlight and nonVital task present in history, which means they were Created
        List<HistoricActivityInstance> historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(3, historicActivityInstance.size()); // start Event, bookFlight, collectPoints

        // check if inputVariables on non-vital task are present -  TODO this with ExtensionProperties
        Task collectPointsTask = taskService.createTaskQuery().taskName("Collect Royality Points").singleResult();
        Map<String, Object> variables = taskService.getVariables(collectPointsTask.getId());

        assertEquals(1, variables.size());
        assertEquals("false", variables.get("isVital"));


        // trigger error on non-vital task
        taskService.handleBpmnError(collectPointsTask.getId(), "errorCode");

        // verify that NonVital Task has been completed
        assertNull(taskService.createTaskQuery().taskName("Collect Royality Points").singleResult());
        historicActivityInstance = historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(4, historicActivityInstance.size()); // start Event, bookFlight, collectPoints
        assertNotNull(historicActivityInstance.get(2).getEndTime()); // 2nd as 3rd is taken by payFlight

        // Non-vital Task should be completed/finished
        collectPointsTask = taskService.createTaskQuery().taskName("Collect Royality Points").singleResult();
        assertNull(collectPointsTask);

        assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());
        completeTask("Pay Flight");
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.retryTaskTest.bpmn20.xml")
    @Test
    public void retryTaskTest() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();
        completeTask("Book Flight");

        // check if task is completed
        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        // check if retry task is activated
        assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());

        // check if inputVariables on retry task are present
        Task payFlightTask = taskService.createTaskQuery().taskName("Pay Flight").singleResult();
        Map<String, Object> variables = taskService.getVariables(payFlightTask.getId());

        assertEquals(3, variables.size());
        assertEquals("true", variables.get("isRetryTask"));
        assertEquals("3", variables.get("retryCount"));
        assertEquals("1", variables.get("retryCooldown"));


        // get prev start time
        Date beforeErrorCreateTime = taskService.createTaskQuery().taskName("Pay Flight").singleResult().getCreateTime();

        // trigger error on non-vital task
        taskService.handleBpmnError(payFlightTask.getId(), "errorCode");

        // check updated start time
        Date afterErrorCreateTime = taskService.createTaskQuery().taskName("Pay Flight").singleResult().getCreateTime();
        assertTrue(beforeErrorCreateTime.before(afterErrorCreateTime));
        assertTrue(TimeUnit.MILLISECONDS.toSeconds(afterErrorCreateTime.getTime() - beforeErrorCreateTime.getTime()) >= (Integer.parseInt((String) variables.get("retryCooldown"))));
        // updated variables
        variables = taskService.getVariables(payFlightTask.getId());


        completeTask("Pay Flight");
        HistoricActivityInstance payFlightTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Pay Flight").singleResult();
        assertNotNull(payFlightTaskHistory.getEndTime());
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.retryTaskTest.bpmn20.xml")
    @Test
    public void retryTaskTestExceedsRetries() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();
        completeTask("Book Flight");

        // check if task is completed
        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        // check if retry task is activated
        assertEquals("Created", taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());

        // check if inputVariables on retry task are present
        Task payFlightTask = taskService.createTaskQuery().taskName("Pay Flight").singleResult();
        Map<String, Object> variables = taskService.getVariables(payFlightTask.getId());

        assertEquals(3, variables.size());
        assertEquals("true", variables.get("isRetryTask"));
        assertEquals("3", variables.get("retryCount"));
        assertEquals("1", variables.get("retryCooldown"));


        while (variables.get("failedAttempts") == null || variables.get("failedAttempts") != null && Integer.parseInt((String) variables.get("failedAttempts")) < Integer.parseInt((String) variables.get("retryCount"))) {
            // get prev start time
            Date beforeErrorCreateTime = taskService.createTaskQuery().taskName("Pay Flight").singleResult().getCreateTime();

            // trigger error on non-vital task
            taskService.handleBpmnError(payFlightTask.getId(), "errorCode");

            // check updated start time
            Date afterErrorCreateTime = taskService.createTaskQuery().taskName("Pay Flight").singleResult().getCreateTime();
            assertTrue(beforeErrorCreateTime.before(afterErrorCreateTime));
            assertTrue(TimeUnit.MILLISECONDS.toSeconds(afterErrorCreateTime.getTime() - beforeErrorCreateTime.getTime()) >= (Integer.parseInt((String) variables.get("retryCooldown"))));
            // updated variables
            variables = taskService.getVariables(payFlightTask.getId());
        }

        // trigger error on retry task that exceeds retry count
        taskService.handleBpmnError(payFlightTask.getId(), "errorCode");


        /**
         * Instance is ended, as missing boundary error catch event leads to ending of execution see {@link org.camunda.bpm.engine.impl.bpmn.helper.BpmnExceptionHandler:136}
         */
        assertThatThrownBy(() -> completeTask("Pay Flight"))
                .hasMessageContaining("Actual there are 0 open tasks with name 'Pay Flight'. Expected at least 1");
        HistoricActivityInstance payFlightTaskHistory = historyService.createHistoricActivityInstanceQuery().activityName("Pay Flight").singleResult();
        assertNotNull(payFlightTaskHistory.getEndTime());
        testRule.assertProcessEnded(processInstanceId);
    }

    /// PLAYGROUND STARTS HERE

    /*
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
*/

/*

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

    }*/
}
