package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SamLayerVersionTransformTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SamTransformProcessor processor = new SamTransformProcessor(mapper);

    private JsonNode template(String properties) throws Exception {
        return mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Layer":{"Type":"AWS::Serverless::LayerVersion","Properties":%s}},
                 "Outputs":{"LayerArn":{"Value":{"Ref":"Layer"}}}}
                """.formatted(properties));
    }

    @Test
    void packagedLayerIsTransformedAndReferencesFollowItsVersion() throws Exception {
        JsonNode result = processor.expandSamTemplate(template("""
                {"ContentUri":{"Bucket":{"Ref":"Bucket"},"Key":"layer.zip","Version":"v1"},
                 "CompatibleRuntimes":["nodejs22.x"],"CompatibleArchitectures":["arm64"],
                 "Description":"shared code","LicenseInfo":"MIT","RetentionPolicy":"Delete"}
                """));
        String id = result.path("Resources").fieldNames().next();
        JsonNode layer = result.path("Resources").path(id);
        assertEquals("AWS::Lambda::LayerVersion", layer.path("Type").asText());
        assertTrue(id.matches("Layer[0-9a-f]{10}"));
        assertEquals(id, result.at("/Outputs/LayerArn/Value/Ref").asText());
        assertEquals("Layer", layer.at("/Properties/LayerName").asText());
        assertEquals("Bucket", layer.at("/Properties/Content/S3Bucket/Ref").asText());
        assertEquals("layer.zip", layer.at("/Properties/Content/S3Key").asText());
        assertEquals("v1", layer.at("/Properties/Content/S3ObjectVersion").asText());
        assertEquals("arm64", layer.at("/Properties/CompatibleArchitectures/0").asText());
        assertEquals("nodejs22.x", layer.at("/Properties/CompatibleRuntimes/0").asText());
        assertEquals("MIT", layer.at("/Properties/LicenseInfo").asText());
        assertEquals("Delete", layer.path("DeletionPolicy").asText());
    }

    @Test
    void stringContentUriAndDefaultRetentionAreSupported() throws Exception {
        JsonNode result = processor.expandSamTemplate(template("{\"ContentUri\":\"s3://layers/code.zip?versionId=abc\"}"));
        JsonNode layer = result.path("Resources").elements().next();
        assertEquals("AWS::Lambda::LayerVersion", layer.path("Type").asText());
        assertEquals("layers", layer.at("/Properties/Content/S3Bucket").asText());
        assertEquals("abc", layer.at("/Properties/Content/S3ObjectVersion").asText());
        assertEquals("Retain", layer.path("DeletionPolicy").asText());
    }

    @Test
    void changedContentProducesANewLogicalId() throws Exception {
        JsonNode first = processor.expandSamTemplate(template("{\"ContentUri\":\"s3://layers/one.zip\"}"));
        JsonNode second = processor.expandSamTemplate(template("{\"ContentUri\":\"s3://layers/two.zip\"}"));
        assertNotEquals(first.path("Resources").fieldNames().next(), second.path("Resources").fieldNames().next());
    }

    @Test
    void referencesDependenciesAndSubstitutionVariablesArePreserved() throws Exception {
        JsonNode input = mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Layer":{"Type":"AWS::Serverless::LayerVersion","Condition":"UseLayer",
                    "Metadata":{"purpose":"shared"},"DeletionPolicy":"Delete",
                    "Properties":{"ContentUri":"s3://layers/code.zip"}},
                  "Consumer":{"Type":"AWS::Lambda::Function","DependsOn":["Layer"],
                    "Properties":{"Layers":[{"Ref":"Layer"}]}},
                  "Other":{"Type":"AWS::S3::Bucket","DependsOn":"Layer"}},
                 "Outputs":{
                   "Direct":{"Value":{"Fn::Sub":"${Layer}-${Layer.Arn}-${!Layer}"}},
                   "Shadowed":{"Value":{"Fn::Sub":["${Layer}-${Layer.Arn}",{"Layer":"literal"}]}},
                   "Attribute":{"Value":{"Fn::GetAtt":["Layer","Arn"]}},
                   "Dotted":{"Value":{"Fn::GetAtt":"Layer.Arn"}}}}
                """);
        JsonNode result = processor.expandSamTemplate(input);
        String id = result.path("Resources").properties().stream()
                .filter(entry -> "AWS::Lambda::LayerVersion".equals(entry.getValue().path("Type").asText()))
                .findFirst().orElseThrow().getKey();
        assertEquals("Layer", input.at("/Resources/Consumer/DependsOn/0").asText());
        assertEquals(id, result.at("/Resources/Consumer/DependsOn/0").asText());
        assertEquals(id, result.at("/Resources/Other/DependsOn").asText());
        assertEquals(id, result.at("/Resources/Consumer/Properties/Layers/0/Ref").asText());
        assertEquals("${" + id + "}-${" + id + ".Arn}-${!Layer}", result.at("/Outputs/Direct/Value/Fn::Sub").asText());
        assertEquals("${Layer}-${" + id + ".Arn}", result.at("/Outputs/Shadowed/Value/Fn::Sub/0").asText());
        assertEquals(id, result.at("/Outputs/Attribute/Value/Fn::GetAtt/0").asText());
        assertEquals(id + ".Arn", result.at("/Outputs/Dotted/Value/Fn::GetAtt").asText());
        assertEquals("UseLayer", result.path("Resources").path(id).path("Condition").asText());
        assertEquals("Delete", result.path("Resources").path(id).path("DeletionPolicy").asText());
    }

    @Test
    void metadataAndResourceRetentionDoNotChangeVersionIdentity() throws Exception {
        JsonNode first = template("{\"ContentUri\":\"s3://layers/code.zip\"}");
        JsonNode second = first.deepCopy();
        ((ObjectNode) second.at("/Resources/Layer"))
                .putObject("Metadata").put("note", "updated documentation");
        ((ObjectNode) second.at("/Resources/Layer"))
                .put("DeletionPolicy", "Delete");
        assertEquals(processor.expandSamTemplate(first).path("Resources").fieldNames().next(),
                processor.expandSamTemplate(second).path("Resources").fieldNames().next());
    }

    @Test
    void unpackagedOrIncompleteContentIsRejectedBeforeProvisioning() throws Exception {
        for (String props : new String[]{"{}", "{\"ContentUri\":\"./layer\"}",
                "{\"ContentUri\":{\"Bucket\":\"layers\"}}",
                "{\"ContentUri\":\"s3://layers/code.zip\",\"RetentionPolicy\":\"invalid\"}"}) {
            JsonNode input = template(props);
            assertThrows(AwsException.class, () -> processor.expandSamTemplate(input));
        }
    }
}
