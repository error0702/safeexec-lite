package fun.autorun.safeexec.core;

/** How dangerous a tool is. Drives default policy: READ auto, WRITE auto within limits, IRREVERSIBLE needs approval. */
public enum ActionLevel { READ, WRITE, IRREVERSIBLE }
