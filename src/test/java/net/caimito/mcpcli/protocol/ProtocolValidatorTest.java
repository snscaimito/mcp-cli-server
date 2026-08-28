package net.caimito.mcpcli.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolValidatorTest {
    private final ProtocolValidator validator = new ProtocolValidator(new ObjectMapper());

    @Test
    void acceptsACompleteDescriptorAndPolymorphicResponses() throws Exception {
        var descriptor = validator.descriptor("""
                {"protocolVersion":"1","name":"customer","version":"1.0","description":"Customers","tools":[
                {"name":"find","title":"Find","description":"Find customers","inputSchema":{"type":"object","additionalProperties":false},"outputSchema":{"type":"object","additionalProperties":false},"annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}}]}""");
        assertThat(descriptor.name()).isEqualTo("customer");
        assertThat(validator.response("""
                {"protocolVersion":"1","requestId":"id","success":true,"result":{}}""")).isInstanceOf(CliProtocol.CliSuccessResponse.class);
        assertThat(validator.response("""
                {"protocolVersion":"1","requestId":"id","success":false,"error":{"code":"NOT_FOUND","message":"Missing","retryable":false,"details":{}}}""")).isInstanceOf(CliProtocol.CliFailureResponse.class);
    }

    @Test
    void rejectsUnknownProtocolFieldsAndRemoteSchemas() {
        assertThatThrownBy(() -> validator.descriptor("""
                {"protocolVersion":"1","name":"customer","version":"1","description":"x","tools":[],"surprise":true}"""))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> validator.validateSchema(new ObjectMapper().readTree("""
                {"type":"object","$ref":"https://evil.example/schema"}"""), "inputSchema"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
