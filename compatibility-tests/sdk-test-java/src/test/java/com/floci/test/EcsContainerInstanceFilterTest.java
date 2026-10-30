package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attribute;
import software.amazon.awssdk.services.ecs.model.ListContainerInstancesRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ECS container instance cluster query filters")
class EcsContainerInstanceFilterTest {

    @Test
    @DisplayName("filters before pagination through the AWS SDK paginator")
    void filtersBeforePagination() {
        try (EcsClient ecs = TestFixtures.ecsClient()) {
            String cluster = "sdk-filter-" + UUID.randomUUID();
            List<String> registered = new ArrayList<>();
            List<String> matching = new ArrayList<>();
            ecs.createCluster(request -> request.clusterName(cluster));
            try {
                for (String role : List.of("other", "web", "web")) {
                    String arn = ecs.registerContainerInstance(request -> request.cluster(cluster)
                            .attributes(Attribute.builder().name("role").value(role).build()))
                            .containerInstance().containerInstanceArn();
                    registered.add(arn);
                    if (role.equals("web")) {
                        matching.add(arn);
                    }
                }
                assertThat(ecs.listContainerInstances(request -> request.cluster(cluster)
                        .filter("runningTasksCount == 1")).containerInstanceArns()).isEmpty();
                ListContainerInstancesRequest request = ListContainerInstancesRequest.builder()
                        .cluster(cluster).filter("attribute:role == web and runningTasksCount == 0")
                        .maxResults(1).build();
                assertThat(ecs.listContainerInstancesPaginator(request).containerInstanceArns().stream().toList())
                        .containsExactlyInAnyOrderElementsOf(matching);
            } finally {
                for (String arn : registered) {
                    ecs.deregisterContainerInstance(request -> request.cluster(cluster)
                            .containerInstance(arn).force(true));
                }
                ecs.deleteCluster(request -> request.cluster(cluster));
            }
        }
    }
}
