package fun.autorun.safeexec.core;

public record PolicyDecision(Decision decision, String reason, String ruleId, int policyVersion) {
    public static PolicyDecision allow(int v) { return new PolicyDecision(Decision.ALLOW, "default", "defaults", v); }
    public static PolicyDecision deny(String reason, String ruleId, int v) { return new PolicyDecision(Decision.DENY, reason, ruleId, v); }
    public static PolicyDecision requireApproval(String reason, String ruleId, int v) { return new PolicyDecision(Decision.REQUIRE_APPROVAL, reason, ruleId, v); }
}
