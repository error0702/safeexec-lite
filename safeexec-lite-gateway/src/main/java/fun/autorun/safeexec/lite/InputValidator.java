package fun.autorun.safeexec.lite;

import java.util.List;

/** Optional extra validation after conversion (plug Bean Validation here). Return a list of problems; empty means valid. */
@FunctionalInterface
public interface InputValidator {
    List<String> validate(Object input);
    static InputValidator none() { return input -> List.of(); }
}
