package fun.autorun.safeexec.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static Tool<Object, Object> named(String name) {
        return new Tool<>() {
            public String name() { return name; }
            public ActionLevel level() { return ActionLevel.WRITE; }
            public RetrySafety retrySafety() { return RetrySafety.NO_SAFE_RETRY; }
            public Class<Object> inputType() { return Object.class; }
            public PolicyFacts policyFacts(Object input, ToolContext ctx) { return PolicyFacts.none(); }
            public Object execute(Object input, ToolContext ctx) { return null; }
        };
    }

    @Test
    @DisplayName("Registering a tool named toggleKillSwitch fails: the kill switch is never an agent tool")
    void killSwitchIsNeverATool() {
        assertThatThrownBy(() -> new ToolRegistry().register(named("toggleKillSwitch")))
                .isInstanceOf(ToolRegistrationException.class);
    }

    @Test
    @DisplayName("Registering a tool named updatePolicy fails: policy is never an agent tool")
    void policyIsNeverATool() {
        assertThatThrownBy(() -> new ToolRegistry().register(named("updatePolicy")))
                .isInstanceOf(ToolRegistrationException.class);
    }

    @Test
    void duplicateNameRejected() {
        ToolRegistry r = new ToolRegistry();
        r.register(named("createPurchase"));
        assertThatThrownBy(() -> r.register(named("createPurchase"))).isInstanceOf(ToolRegistrationException.class);
    }
}
