package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerInstanceQueryTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "runningTasksCount == 2", "runningTasksCount != 1", "runningTasksCount > 1",
            "runningTasksCount >= 2", "runningTasksCount < 10", "runningTasksCount <= 2",
            "runningTasksCount equals 2", "runningTasksCount not_equals 0",
            "runningTasksCount greater_than 1", "runningTasksCount greater_than_equal 2",
            "runningTasksCount less_than 10", "runningTasksCount less_than_equal 2",
            "agentConnected == true", "agentConnected in [false, true]", "agentConnected !in [false]",
            "agentConnected =~ tr.*", "agentConnected !~ fal.*", "ec2InstanceId == i-abcd1234",
            "ec2InstanceId in ['i-abcd1234', 'i-wxyx7890']", "ec2InstanceId not_in [i-other]",
            "ec2InstanceId =~ i-[a-z0-9]+", "ec2InstanceId matches '(i-abcd1234|i-other)'",
            "attribute:ecs.instance-type == t2.small", "attribute:ecs.instance-type matches t2.*",
            "attribute:ecs.availability-zone in [us-east-1a, us-east-1b]",
            "attribute:ecs.instance-type =~ t2.* and attribute:ecs.availability-zone != us-east-1d",
            "attribute:custom exists", "attribute:missing !exists", "attribute:missing not_exists",
            "attribute:missing != value", "attribute:label == 'two words'",
            "task:group == service:production", "not(task:group == database)",
            "agentVersion < 1.14.5", "agentVersion > 1.2.100", "agentVersion == 1.10.0",
            "registeredAt < 2018-02-13", "registeredAt == 2018-02-12T12:00:00+00:00",
            "registeredAt == 2018-02-12T12:00:00", "registeredAt >= 2018-02-12T12:00:00Z",
            "runningTasksCount==2&&agentConnected==true", "!(runningTasksCount == 0)",
            "runningTasksCount == 2 or runningTasksCount == 0 and agentConnected == false",
            "(runningTasksCount == 0 || runningTasksCount == 2) && not agentConnected == false"
    })
    void matchesDocumentedSubjectsOperatorsAndCompoundExpressions(String expression) {
        assertTrue(matches(expression), expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "runningTasksCount == 1", "runningTasksCount > 10", "agentConnected == false",
            "ec2InstanceId not_matches i-.*", "attribute:missing exists", "attribute:custom !exists",
            "attribute:missing == value", "attribute:custom == value", "task:group == database",
            "agentVersion >= 1.14.5", "registeredAt > 2018-02-13",
            "(runningTasksCount == 2 or runningTasksCount == 0) and agentConnected == false"
    })
    void excludesNonMatchingInstances(String expression) {
        assertFalse(matches(expression), expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "unknown == x", "attribute: == x", "runningTasksCount", "runningTasksCount = 1",
            "runningTasksCount == two", "runningTasksCount == -1", "runningTasksCount in [1,2]",
            "agentVersion =~ .*", "registeredAt == yesterday", "agentVersion == 1..2",
            "agentConnected > false", "agentConnected exists", "ec2InstanceId in []",
            "ec2InstanceId in [i-one,]", "ec2InstanceId in [i-one", "ec2InstanceId == 'unterminated",
            "ec2InstanceId =~ '['", "(runningTasksCount == 1", "runningTasksCount == 1)",
            "runningTasksCount == 1 junk", "runningTasksCount == 1 or unknown == x",
            "runningTasksCount == 1 and", "not", "()"
    })
    void rejectsInvalidFiltersEvenBeforeAnyInstancesAreEvaluated(String expression) {
        AwsException error = assertThrows(AwsException.class,
                () -> ContainerInstanceQuery.compile(expression, ignored -> List.of()));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void absentFilterAndAbsentOptionalFieldsWork() {
        assertTrue(ContainerInstanceQuery.compile(null, ignored -> List.of()).test(new ContainerInstance()));
        assertFalse(ContainerInstanceQuery.compile("agentVersion < 2", ignored -> List.of())
                .test(new ContainerInstance()));
        assertFalse(ContainerInstanceQuery.compile("task:group == service:production", ignored -> List.of())
                .test(instance()));
    }

    @Test
    void taskCountUsesRunningTasksRatherThanAStaleStoredCounter() {
        ContainerInstance instance = instance();
        instance.setRunningTasksCount(99);
        List<EcsTask> tasks = List.of("RUNNING", "PENDING", "STOPPED").stream().map(status -> {
            EcsTask task = new EcsTask();
            task.setLastStatus(status);
            task.setDesiredStatus(status.equals("STOPPED") ? "STOPPED" : "RUNNING");
            return task;
        }).toList();
        assertTrue(ContainerInstanceQuery.compile("runningTasksCount == 2", ignored -> tasks).test(instance));
        assertFalse(ContainerInstanceQuery.compile("runningTasksCount == 99", ignored -> tasks).test(instance));
        tasks.getFirst().setDesiredStatus("STOPPED");
        assertTrue(ContainerInstanceQuery.compile("runningTasksCount == 1", ignored -> tasks).test(instance));
    }

    private static boolean matches(String expression) {
        EcsTask service = new EcsTask();
        service.setGroup("service:production");
        service.setLastStatus("RUNNING");
        service.setDesiredStatus("RUNNING");
        EcsTask standalone = new EcsTask();
        standalone.setGroup("family:web");
        standalone.setLastStatus("RUNNING");
        standalone.setDesiredStatus("RUNNING");
        return ContainerInstanceQuery.compile(expression, ignored -> List.of(service, standalone))
                .test(instance());
    }

    private static ContainerInstance instance() {
        ContainerInstance instance = new ContainerInstance();
        instance.setEc2InstanceId("i-abcd1234");
        instance.setAgentConnected(true);
        instance.setVersionInfo(Map.of("agentVersion", "1.10.0"));
        instance.setRegisteredAt(Instant.parse("2018-02-12T12:00:00Z"));
        instance.setAttributes(List.of(new Attribute("ecs.instance-type", "t2.small", null, null),
                new Attribute("ecs.availability-zone", "us-east-1a", null, null),
                new Attribute("custom", null, null, null), new Attribute("label", "two words", null, null)));
        return instance;
    }
}
