package fun.autorun.safeexec.core;

/**
 * Turns a business event into an ActionProposal. The planner (an LLM, or rules) never executes anything;
 * every proposed ToolCall goes through the ToolGateway. SafeExec does not care which model produced it.
 */
public interface AgentPlanner {
    ActionProposal plan(PlanningRequest request);
}
