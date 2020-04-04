package run.qontract.core

import run.qontract.core.value.Value

data class IgnoreFacts(val state: Map<String, Value> = emptyMap()) : FactStore {
    override fun match(sampleValue: Value, key: String): Result = Result.Success()

    override fun has(key: String): Boolean = state.containsKey(key)
    override fun get(key: String): Value = state.getValue(key)
    override fun makeCopy(state: Map<String, Value>): FactStore = copy(state = state)
}