package org.camunda.bpm.engine.test.bpmn.event.compensate.helper;

import ch.qos.logback.classic.Level;
import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.impl.bpmn.helper.CompensationUtil;
import org.camunda.bpm.engine.runtime.VariableInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.util.PluggableProcessEngineTest;
import org.camunda.commons.testing.ProcessEngineLoggingRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.After;


import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.*;
import static org.junit.Assert.assertNotNull;

public class CompensationConceptsTests extends PluggableProcessEngineTest {

    protected static final String BPMN_BEHAVIOR_LOGGER = "org.camunda.bpm.engine.bpmn.behavior";

    @Rule
    public ProcessEngineLoggingRule loggingRule = new ProcessEngineLoggingRule()
            .watch(BPMN_BEHAVIOR_LOGGER)
            .level(Level.ALL);

    private void completeTask(String taskName) {
        List<Task> tasks = taskService.createTaskQuery().taskName(taskName).list();

        assertFalse("Actual there are " + tasks.size() + " open tasks with name '" + taskName + "'. Expected at least" +
                " 1", tasks.isEmpty());

        Iterator<Task> taskIterator = tasks.iterator();
        Task task = taskIterator.next();
        taskService.complete(task.getId());
    }

    @After
    public void tearDown() throws Exception {
        CompensationUtil.resetSavepointFlags();
    }

    @Before
    public void setUp() throws Exception {
        CompensationUtil.resetSavepointFlags();
    }

    /**
     * Requirements:
     * - Non-vital task: If error is thrown during task, continu eexecution
     * - Retry task: Task is retried x times, with at least y cooldown and if not successfull, propagates error
     * - Savepoint:
     *      - Partial compensation supported
     *      - Re-execution after the savepoint is supported
     *      - Savepoint is used only once
     * - Alternative Paths Gateway:
     *      - State in the gateway that defines which outgoing flow to take
     *      - If all outgoing flows fail, default flow is taken (default behaviour of XOR)
     *      - If AP successfully joined, savepoint will be disregarded
     *
     * Not supported:
     * - Multiple savepoints in a process, as currently flags are used and with multiple ones, maps/lists would be
     * required which could leads to a complicated algorithm to which is actually the closest savepoint to be reached
     * from this position
     * - Combining savepoints and APs in one process instance
     * - Other task types than user tasks (not tested with other types)
     */

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest" +
            ".simpleCompensationTest.bpmn20.xml")
    @Test
    public void simpleCompensationTest() {
        // this starts a process instance, using the model in the xml file attached to the test and identified by its
        // process
        // definition key, mentioned at the top of the xml
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        // the start event and first task is part of the history after initialization
        List<HistoricActivityInstance> currentHistory = historyService.createHistoricActivityInstanceQuery().list();
        assertEquals(currentHistory.size(), 2);

        // the running tasks can be accessed via the taskService, only Book Flight task is currently running
        List<Task> currentlyRunningTasks = taskService.createTaskQuery().list();
        assertEquals(currentlyRunningTasks.size(), 1);
        Task bookFlightTask = currentlyRunningTasks.get(0);
        assertEquals("Book Flight", bookFlightTask.getName());
        // in the historyService a running task can be identified by having a start time, but no end time
        HistoricActivityInstance bookFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Book Flight").singleResult();
        assertNotNull(bookFlightTaskHistory.getStartTime());
        assertNull(bookFlightTaskHistory.getEndTime());

        // this completes the task
        completeTask("Book Flight");

        // now the end time for book flight should be available, as its completed
        bookFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Book Flight").singleResult();
        assertNotNull(bookFlightTaskHistory.getEndTime());
        // but the runtime service has still a task in execution, which is the compensation task, that has been
        // triggered
        currentlyRunningTasks = taskService.createTaskQuery().list();
        assertEquals(currentlyRunningTasks.size(), 1);
        assertNotEquals("Book Flight", currentlyRunningTasks.get(0).getName());
        assertEquals("Cancel Flight", currentlyRunningTasks.get(0).getName());

        // the compensation event is automatically thrown and triggers the compensation task to start running
        currentHistory = historyService.createHistoricActivityInstanceQuery().list();
        assertEquals(4, currentHistory.size());  // startEvent, BookFlight, CompensationThrowEndEvent, CancelFlight
        HistoricActivityInstance compensationThrowEvent =
                historyService.createHistoricActivityInstanceQuery().activityId("compensationThrowEndEvent").singleResult();
        assertNotNull(compensationThrowEvent);


        // Cancel Flight is active
        HistoricActivityInstance cancelFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Flight").singleResult();
        assertNotNull(cancelFlightTaskHistory);
        completeTask("Cancel Flight");

        // compensation throw end event is then also completed
        compensationThrowEvent = historyService.createHistoricActivityInstanceQuery().activityId(
                "compensationThrowEndEvent").singleResult();
        assertNotNull(compensationThrowEvent.getEndTime());


        // with this the process instance is verified to be completed. Process instances can be accessed via the
        // runtime service
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest" +
            ".alternativePathsTest.bpmn20.xml")
    @Test
    public void alternativePathsTest() {
        // The savepoint of the AP gateway is explicitly modelled, as it was not possible to add a compensation catch
        // handler to a gateway. Furthermore, the compensation and error events are explicit, as with the subprocesses
        // it was not possible to trigger the compensation, as only finished subprocesses would have reacted to the
        // compensation throw event. For this, major changes in the compensation behaviour would be necessary.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Savepoint");

        HistoricActivityInstance taskATaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Book Car").singleResult();
        assertNotNull(taskATaskHistory.getStartTime());

        completeTask("Book Car");

        Task taskB = taskService.createTaskQuery().taskName("Reserve Equipment").singleResult();
        taskService.handleBpmnError(taskB.getId(), "errorCode");

        HistoricActivityInstance compATaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(compATaskHistory.getStartTime());

        completeTask("Cancel Car");

        completeTask("Book Train");
        completeTask("Book Seat Reservations");
        completeTask("Pay Booking");

        testRule.assertProcessEnded(processInstanceId);
    }


    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest" +
            ".alternativePathsTest.bpmn20.xml")
    @Test
    public void alternativePathsRevokeSavepointIfSuccessfullyJoinedTest() {
        // This tests that a compensation triggered after an AP Gateways construct, will disregard the savepoint if
        // the AP construct has been is successfully joined.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Savepoint");
        completeTask("Book Car");
        completeTask("Reserve Equipment");

        Task payBookingTask = taskService.createTaskQuery().taskName("Pay Booking").singleResult();
        taskService.handleBpmnError(payBookingTask.getId(), "errorCode");

        completeTask("Cancel Equipment");
        completeTask("Cancel Car");
        completeTask("CompSavepoint");
        completeTask("Cancel Flight");

        testRule.assertProcessEnded(processInstanceId);
    }


    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest" +
            ".alternativePathsTest.bpmn20.xml")
    @Test
    public void alternativePathsTestTakesDefaultPath() {
        // This tests that the default flow is taken in case of no alternatives being left.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Savepoint");

        HistoricActivityInstance taskATaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Book Car").singleResult();
        assertNotNull(taskATaskHistory.getStartTime());

        completeTask("Book Car");

        Task taskB = taskService.createTaskQuery().taskName("Reserve Equipment").singleResult();
        taskService.handleBpmnError(taskB.getId(), "errorCode");

        HistoricActivityInstance compATaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(compATaskHistory.getStartTime());

        completeTask("Cancel Car");

        completeTask("Book Train");

        Task taskD = taskService.createTaskQuery().taskName("Book Seat Reservations").singleResult();
        taskService.handleBpmnError(taskD.getId(), "errorCode");

        HistoricActivityInstance compCTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Train").singleResult();
        assertNotNull(compCTaskHistory.getStartTime());
        completeTask("Cancel Train");

        completeTask("Pay Booking");

        testRule.assertProcessEnded(processInstanceId);
    }


    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.savepointTest" +
            ".bpmn20.xml")
    @Test
    public void savepointTest() {
        // This test verifies the correct re-execution after a partial compensation. Book hotel is the savepoint.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Book Hotel");
        completeTask("Book Car");

        Task paymentTask = taskService.createTaskQuery().taskName("Pay Booking").singleResult();
        taskService.handleBpmnError(paymentTask.getId(), "errorCode");

        HistoricActivityInstance cancelCarTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(cancelCarTaskHistory);
        assertNotNull(cancelCarTaskHistory.getStartTime());
        assertNull(cancelCarTaskHistory.getEndTime());

        completeTask("Cancel Car");

        cancelCarTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(cancelCarTaskHistory.getEndTime());

        HistoricActivityInstance cancelHotelTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Hotel").singleResult();
        assertNull(cancelHotelTaskHistory);

        HistoricActivityInstance cancelFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Flight").singleResult();
        assertNull(cancelFlightTaskHistory);

        List<HistoricActivityInstance> bookHotelTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Book Hotel").list();
        assertEquals(1, bookHotelTaskHistory.size());

        completeTask("Book Car");
        completeTask("Pay Booking");

        testRule.assertProcessEnded(processInstanceId);
    }


    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.savepointTest" +
            ".bpmn20.xml")
    @Test
    public void savepointIgnoredOnSecondErrorTest() {
        // This test verifies that a second visit to a savepoint during a compensation will ignore its savepoint
        // properties and continues with the compensation to the beginning of the process.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();

        completeTask("Book Flight");
        completeTask("Book Hotel");
        completeTask("Book Car");

        Task paymentTask = taskService.createTaskQuery().taskName("Pay Booking").singleResult();
        taskService.handleBpmnError(paymentTask.getId(), "errorCode");

        HistoricActivityInstance cancelCarTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(cancelCarTaskHistory);
        assertNotNull(cancelCarTaskHistory.getStartTime());
        assertNull(cancelCarTaskHistory.getEndTime());

        completeTask("Cancel Car");

        cancelCarTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Car").singleResult();
        assertNotNull(cancelCarTaskHistory.getEndTime());

        HistoricActivityInstance cancelHotelTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Hotel").singleResult();
        assertNull(cancelHotelTaskHistory);

        HistoricActivityInstance cancelFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Cancel Flight").singleResult();
        assertNull(cancelFlightTaskHistory);

        List<HistoricActivityInstance> bookHotelTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Book Hotel").list();
        assertEquals(1, bookHotelTaskHistory.size());

        completeTask("Book Car");

        paymentTask = taskService.createTaskQuery().taskName("Pay Booking").singleResult();
        taskService.handleBpmnError(paymentTask.getId(), "errorCode");

        completeTask("Cancel Car");
        completeTask("Cancel Hotel");
        completeTask("Cancel Flight");


        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest" +
            ".nonVitalTaskTest.bpmn20.xml")
    @Test
    public void nonVitalTaskTest() {
        // Tests whether an error in a non-vital task is ignored and the process instance completes
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();

        completeTask("Book Flight");

        // check if task is completed
        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        // check if non-vital task is activated
        assertEquals("Created",
                taskService.createTaskQuery().taskDefinitionKey("collectPoints").singleResult().getTaskState());

        // check if start event, bookFlight and nonVital task present in history, which means they were Created
        List<HistoricActivityInstance> historicActivityInstance =
                historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(3, historicActivityInstance.size()); // start Event, bookFlight, collectPoints

        // check if inputVariables on non-vital task are present
        Task collectPointsTask = taskService.createTaskQuery().taskName("Collect Royality Points").singleResult();
        Map<String, Object> variables = taskService.getVariables(collectPointsTask.getId());

        assertEquals(1, variables.size());
        assertEquals("false", variables.get("isVital"));


        // trigger error on non-vital task
        taskService.handleBpmnError(collectPointsTask.getId(), "errorCode");

        // verify that NonVital Task has been completed
        assertNull(taskService.createTaskQuery().taskName("Collect Royality Points").singleResult());
        historicActivityInstance =
                historyService.createHistoricActivityInstanceQuery().orderByHistoricActivityInstanceStartTime().asc().list();
        assertEquals(4, historicActivityInstance.size()); // start Event, bookFlight, collectPoints
        assertNotNull(historicActivityInstance.get(2).getEndTime()); // 2nd as 3rd is taken by payFlight

        // Non-vital Task should be completed/finished
        collectPointsTask = taskService.createTaskQuery().taskName("Collect Royality Points").singleResult();
        assertNull(collectPointsTask);

        assertEquals("Created",
                taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());
        completeTask("Pay Flight");
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.retryTaskTest" +
            ".bpmn20.xml")
    @Test
    public void retryTaskTest() {
        // Tests, that a retry task retries actually failed task and important task parameters are updated. Also
        // tests if retryCooldown is waited for.
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();
        completeTask("Book Flight");

        // check if task is completed
        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        // check if retry task is activated
        assertEquals("Created",
                taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());

        // check if inputVariables on retry task are present
        Task payFlightTask = taskService.createTaskQuery().taskName("Pay Flight").singleResult();
        Map<String, Object> variables = taskService.getVariables(payFlightTask.getId());

        assertEquals(1, variables.size());
        TreeMap<String, String> variableMap = (TreeMap<String, String>) variables.get("isRetryTask");
        assertEquals("true", variableMap.get("isRetryTask"));
        assertEquals("3", variableMap.get("retryCount"));
        assertEquals("10", variableMap.get("retryCooldown"));


        // get prev start time
        Date beforeErrorCreateTime =
                new Date();

        // trigger error on retry task
        taskService.handleBpmnError(payFlightTask.getId(), "errorCode");

        // check updated start time
        Date afterErrorCreateTime = taskService.createTaskQuery().taskName("Pay Flight").singleResult().getCreateTime();
        assertTrue(beforeErrorCreateTime.before(afterErrorCreateTime));
        assertTrue(TimeUnit.MILLISECONDS.toSeconds(afterErrorCreateTime.getTime()
                - beforeErrorCreateTime.getTime()) >= (Integer.parseInt(variableMap.get("retryCooldown"))));

        HistoricActivityInstance payFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Pay Flight").singleResult();

        // check if retry task is ready to be executed
        assertEquals("Created",
                taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());

        completeTask("Pay Flight");
        payFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Pay Flight").singleResult();
        assertNotNull(payFlightTaskHistory.getEndTime());
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.retryTaskTest" +
            ".bpmn20.xml")
    @Test
    public void retryTaskExceedsRetriesTest() {
        // tests that in case of a retry tasks exceeding the retryCount the error is still propagated
        String processInstanceId = runtimeService.startProcessInstanceByKey("flightBookingProcess").getId();
        completeTask("Book Flight");

        // check if task is completed
        Task bookFlightTask = taskService.createTaskQuery().taskName("Book Flight").singleResult();
        assertNull(bookFlightTask);

        // check if retry task is activated
        assertEquals("Created",
                taskService.createTaskQuery().taskDefinitionKey("payFlight").singleResult().getTaskState());

        // check if inputVariables on retry task are present
        Task payFlightTask = taskService.createTaskQuery().taskName("Pay Flight").singleResult();
        Map<String, Object> variables = taskService.getVariables(payFlightTask.getId());

        assertEquals(1, variables.size());
        TreeMap<String, String> variableMap = (TreeMap<String, String>) variables.get("isRetryTask");
        assertEquals("true", variableMap.get("isRetryTask"));
        assertEquals("3", variableMap.get("retryCount"));
        assertEquals("10", variableMap.get("retryCooldown"));


        while (variables.get("failedAttempts") == null || variables.get("failedAttempts") != null
                && Integer.parseInt((String) variables.get("failedAttempts"))
                < Integer.parseInt(variableMap.get("retryCount"))) {
            // get prev start time
            Date beforeErrorCreateTime =
                    new Date();

            // trigger error on non-vital task
            taskService.handleBpmnError(payFlightTask.getId(), "errorCode");

            // check updated start time
            Date afterErrorCreateTime =
                    taskService.createTaskQuery().taskName("Pay Flight").singleResult().getCreateTime();
            assertTrue(beforeErrorCreateTime.before(afterErrorCreateTime));
            assertTrue(TimeUnit.MILLISECONDS.toSeconds(afterErrorCreateTime.getTime()
                    - beforeErrorCreateTime.getTime()) >= (Integer.parseInt(variableMap.get("retryCooldown"))));
            // updated variables
            variables = taskService.getVariables(payFlightTask.getId());
        }

        // trigger error on retry task that exceeds retry count
        taskService.handleBpmnError(payFlightTask.getId(), "errorCode");

        // verifies that error has occurred
        assertThat(loggingRule.getFilteredLog(BPMN_BEHAVIOR_LOGGER, "Execution is ended (none end event semantics)").size()).isEqualTo(1);
        assertThat(loggingRule.getFilteredLog(BPMN_BEHAVIOR_LOGGER, "no catching boundary event was defined").size()).isEqualTo(1);

        /**
         * Instance is ended, as missing boundary error catch event leads to ending of execution see
         * {@link org.camunda.bpm.engine.impl.bpmn.helper.BpmnExceptionHandler:136}
         */
        assertThatThrownBy(() -> completeTask("Pay Flight"))
                .hasMessageContaining("Actual there are 0 open tasks with name 'Pay Flight'. Expected at least 1");
        HistoricActivityInstance payFlightTaskHistory =
                historyService.createHistoricActivityInstanceQuery().activityName("Pay Flight").singleResult();
        assertNotNull(payFlightTaskHistory.getEndTime());
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.shallowSubprocessCompensationTest" +
            ".bpmn20.xml")
    @Test
    public void shallowSubprocessCompensationTest() {
        // This test verifies, that a subprocess having a boundary compensation catch event triggers the shallow
        // compensation task.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();
        completeTask("Task A");
        completeTask("Shallow Comp A");
        testRule.assertProcessEnded(processInstanceId);
    }

    @Deployment(resources = "org/camunda/bpm/engine/test/bpmn/event/compensate/CompensationConceptsTest.deepSubprocessCompensationTest" +
            ".bpmn20.xml")
    @Test
    public void deepSubprocessCompensationTest() {
        // This test verifies, that a subprocess without a boundary compensation catch event is deeply compensated.
        String processInstanceId = runtimeService.startProcessInstanceByKey("bookingProcess").getId();
        completeTask("Task A");
        completeTask("Comp A");
        testRule.assertProcessEnded(processInstanceId);
    }
}
