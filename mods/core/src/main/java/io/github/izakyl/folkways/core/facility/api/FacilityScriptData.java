package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import java.util.LinkedHashMap;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;

final class FacilityScriptData {

    private FacilityScriptData() {
    }

    /** A data reading also shows its fields, so a script reads {@code r["x"]} rather than parsing its SNBT. */
    static Dict<String, Object> snapshot(ScriptValues decoder, Map<String, Value> values, Mutability scope)
            throws EvalException {
        return decoder.snapshot(values, DefinitionCodec::readout, scope);
    }

    static Dict<String, Object> readings(ScriptValues decoder, Map<String, Value> values, Mutability scope)
            throws EvalException {
        if (!values.containsKey("_target")) {
            return snapshot(decoder, values, scope);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        values.forEach((name, value) -> fields.put(name, switch (name) {
            case "_target", "_target_approach", "_sweep_drive" -> DefinitionCodec.plain(((Value.Data) value).tag());
            default -> DefinitionCodec.readout(value);
        }));
        return decoder.snapshot(fields, value -> value, scope);
    }
}
