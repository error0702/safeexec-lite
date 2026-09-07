package fun.autorun.safeexec.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The only thing the Policy Engine understands. Tools translate their business input into facts;
 * the engine never sees business JSON.
 */
public final class PolicyFacts {
    private final Map<String, Money> money;
    private final Map<String, String> attributes;
    private final String group;

    private PolicyFacts(Map<String, Money> money, Map<String, String> attributes, String group) {
        this.money = Collections.unmodifiableMap(new LinkedHashMap<>(money));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.group = group;
    }
    public static Builder builder() { return new Builder(); }
    public static PolicyFacts none() { return builder().build(); }

    public Optional<Money> money(String name) { return Optional.ofNullable(money.get(name)); }
    public Map<String, Money> allMoney() { return money; }
    public Optional<String> attribute(String name) { return Optional.ofNullable(attributes.get(name)); }
    public Map<String, String> attributes() { return attributes; }
    public Optional<String> group() { return Optional.ofNullable(group); }

    public static final class Builder {
        private final Map<String, Money> money = new LinkedHashMap<>();
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String group;
        public Builder money(String name, Money value) { money.put(name, value); return this; }
        public Builder attribute(String name, String value) { attributes.put(name, value); return this; }
        public Builder group(String group) { this.group = group; return this; }
        public PolicyFacts build() { return new PolicyFacts(money, attributes, group); }
    }
}
