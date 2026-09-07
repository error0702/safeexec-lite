package fun.autorun.safeexec.core;

import java.math.BigDecimal;
import java.util.Objects;

/** Minimal money value. Currency is an ISO-4217 code; policy compares only same-currency amounts. */
public record Money(String currency, BigDecimal amount) {
    public Money {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        currency = currency.toUpperCase();
    }
    public static Money of(String currency, long amount) { return new Money(currency, BigDecimal.valueOf(amount)); }
    public static Money of(String currency, BigDecimal amount) { return new Money(currency, amount); }
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }
    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
    }
}
