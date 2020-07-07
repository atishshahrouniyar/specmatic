package run.qontract.core.pattern

import run.qontract.core.*
import run.qontract.core.value.ListValue
import run.qontract.core.value.Value

data class ListPattern(override val pattern: Pattern) : Pattern, EncompassableList {
    override fun getEncompassableList(count: Int, resolver: Resolver): List<Pattern> {
        val resolvedPattern = resolvedHop(pattern, resolver)
        return 0.until(count).map { resolvedPattern }
    }

    override fun isEndless(): Boolean = true

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData !is ListValue)
            return when {
                resolvedHop(pattern, resolver) is XMLPattern -> mismatchResult("xml nodes", sampleData)
                else -> mismatchResult("JSON array", sampleData)
            }

        val resolver = withEmptyType(pattern, resolver)

        return sampleData.list.asSequence().map {
            resolver.matchesPattern(null, pattern, it)
        }.mapIndexed { index, result -> Pair(index, result) }.find { it.second is Result.Failure }?.let { (index, result) ->
            when(result) {
                is Result.Failure -> result.breadCrumb("[$index]")
                else -> Result.Success()
            }
        } ?: Result.Success()
    }

    override fun generate(resolver: Resolver): Value {
        val resolver = withEmptyType(pattern, resolver)
        return pattern.listOf(0.until(randomNumber(10)).mapIndexed{ index, _ ->
            attempt(breadCrumb = "[$index (random)]") { pattern.generate(resolver) }
        }, resolver)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        val resolver = withEmptyType(pattern, resolver)
        return attempt(breadCrumb = "[]") { pattern.newBasedOn(row, resolver).map { ListPattern(it) } }
    }
    override fun parse(value: String, resolver: Resolver): Value = parsedJSONStructure(value)

    override fun patternSet(resolver: Resolver): List<Pattern> {
        val resolver = withEmptyType(pattern, resolver)
        return pattern.patternSet(resolver)
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        val thisResolver = withEmptyType(pattern, thisResolver)
        val otherResolver = withEmptyType(pattern, otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolver, thisResolver)
            is JSONArrayPattern -> {
                try {
                    val results = otherPattern.getEncompassableList(otherResolver).asSequence().mapIndexed { index, otherPatternEntry ->
                        Pair(index, pattern.encompasses(otherPatternEntry, thisResolver, otherResolver))
                    }

                    results.find { it.second is Result.Failure }?.let { result -> result.second.breadCrumb("[${result.first}]") } ?: Result.Success()
                } catch (e: ContractException) {
                    Result.Failure(e.report())
                }
            }
            is XMLPattern -> {
                try {
                    val results = otherPattern.getEncompassables(otherResolver).asSequence().mapIndexed { index, otherPatternEntry ->
                        Pair(index, pattern.encompasses(resolvedHop(otherPatternEntry, otherResolver), thisResolver, otherResolver))
                    }

                    results.find { it.second is Result.Failure }?.let { result -> result.second.breadCrumb("[${result.first}]") } ?: Result.Success()
                } catch (e: ContractException) {
                    Result.Failure(e.report())
                }
            }
            !is ListPattern -> Result.Failure("Expected array or list type, got ${otherPattern.typeName}")
            else -> otherPattern.fitsWithin(patternSet(thisResolver), otherResolver, thisResolver)
        }
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        val resolver = withEmptyType(pattern, resolver)
        return pattern.listOf(valueList, resolver)
    }

    override val typeName: String = "list of ${pattern.typeName}"
}

private fun withEmptyType(pattern: Pattern, resolver: Resolver): Resolver {
    val patternSet = pattern.patternSet(resolver)

    val hasXML = patternSet.any { resolvedHop(it, resolver) is XMLPattern }

    val emptyType = if(hasXML) EmptyStringPattern else NullPattern

    return resolver.copy(newPatterns = resolver.newPatterns.plus("(empty)" to emptyType))
}
