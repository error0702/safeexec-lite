package fun.autorun.safeexec.core;

/** When shadow mode covers a tool, WRITE and IRREVERSIBLE calls are recorded but never executed. */
public interface ShadowMode {
    boolean blocks(Tool<?, ?> tool, PolicyFacts facts);
}
