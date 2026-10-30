package io.github.hectorvent.floci.services.route53;

import static io.restassured.RestAssured.given;

final class Route53HostedZoneTestSupport {

    private Route53HostedZoneTestSupport() {}

    static String createZone(String name, String callerReference) {
        String request = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference);
        String location = given().contentType("application/xml").body(request)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
