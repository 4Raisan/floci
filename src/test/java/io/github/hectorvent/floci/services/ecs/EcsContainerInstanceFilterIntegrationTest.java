package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class EcsContainerInstanceFilterIntegrationTest {

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void runningTaskFilterExcludesANewlyRegisteredInstance() {
        String cluster = "filter-" + System.nanoTime();
        call("CreateCluster", Map.of("clusterName", cluster));
        String arn = call("RegisterContainerInstance", Map.of("cluster", cluster))
                .jsonPath().getString("containerInstance.containerInstanceArn");

        call("ListContainerInstances", Map.of("cluster", cluster, "filter", "runningTasksCount == 1"))
                .then().body("containerInstanceArns", empty());
        assertEquals(arn, call("ListContainerInstances",
                Map.of("cluster", cluster, "filter", "runningTasksCount == 0"))
                .jsonPath().getString("containerInstanceArns[0]"));
    }

    @Test
    void attributeFilterIsAppliedBeforePagingAndCombinedWithStatus() {
        String cluster = "filter-page-" + System.nanoTime();
        call("CreateCluster", Map.of("clusterName", cluster));
        register(cluster, "other");
        String first = register(cluster, "web");
        String second = register(cluster, "web");
        String draining = register(cluster, "web");
        call("UpdateContainerInstancesState", Map.of("cluster", cluster,
                "containerInstances", List.of(draining), "status", "DRAINING"));

        Map<String, Object> request = new HashMap<>(Map.of("cluster", cluster,
                "status", "ACTIVE", "filter", "attribute:role == web", "maxResults", 1));
        Response page = call("ListContainerInstances", request);
        List<String> arns = new ArrayList<>(page.jsonPath().getList("containerInstanceArns", String.class));
        assertEquals(1, arns.size());
        request.put("nextToken", page.jsonPath().getString("nextToken"));
        Response last = call("ListContainerInstances", request);
        arns.addAll(last.jsonPath().getList("containerInstanceArns", String.class));
        assertEquals(2, arns.size());
        assertEquals(new HashSet<>(List.of(first, second)), new HashSet<>(arns));
        assertNull(last.jsonPath().getString("nextToken"));
    }

    @Test
    void malformedFilterIsRejectedForAnEmptyCluster() {
        String cluster = "filter-invalid-" + System.nanoTime();
        call("CreateCluster", Map.of("clusterName", cluster));
        given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113.ListContainerInstances")
                .body(Map.of("cluster", cluster, "filter", "runningTasksCount == invalid"))
                .post("/").then().statusCode(400)
                .body("__type", endsWith("InvalidParameterException"));
    }

    private static String register(String cluster, String role) {
        return call("RegisterContainerInstance", Map.of("cluster", cluster,
                "attributes", List.of(Map.of("name", "role", "value", role))))
                .jsonPath().getString("containerInstance.containerInstanceArn");
    }

    private static Response call(String action, Map<String, ?> request) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonEC2ContainerServiceV20141113." + action)
                .body(request).post("/").then().statusCode(200).extract().response();
    }
}
