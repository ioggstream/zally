package de.zalando.zally.rule

import de.zalando.zally.rule.api.Context
import de.zalando.zally.rule.api.Violation
import de.zalando.zally.util.ast.JsonPointers
import io.swagger.parser.SwaggerParser
import io.swagger.parser.util.SwaggerDeserializationResult
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.converter.SwaggerConverter
import io.swagger.v3.parser.core.models.AuthorizationValue
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.core.models.SwaggerParseResult
import io.swagger.v3.parser.util.ResolverFully
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.regex.Pattern

@Component
class DefaultContextFactory(@Value("\${zally.propagateAuthorizationUrls:}") val propagateAuthorizationUrls: Array<Pattern> = emptyArray()) {

    private val log = LoggerFactory.getLogger(DefaultContextFactory::class.java)

    init {
        log.info("propagate authorization for ${propagateAuthorizationUrls.contentToString()}")
    }

    fun createOpenApiContext(content: String, authorization: String? = null): ContentParseResult<Context> {
        val parseResult = parseOpenApi(content, authorization)
        if (parseResult !is ContentParseResult.ParsedSuccessfully) return parseResult.of()

        val resolveResult = resolveOpenApi(parseResult.result)
        if (resolveResult !is ContentParseResult.ParsedSuccessfully) return resolveResult.of()

        return ContentParseResult.ParsedSuccessfully(DefaultContext(content, parseResult.result.openAPI))
    }

    fun createSwaggerContext(content: String): ContentParseResult<Context> {
        val parseResult = parseSwagger(content)
        if (parseResult !is ContentParseResult.ParsedSuccessfully) return parseResult.of()

        val convertResult = convertSwaggerToOpenAPI(parseResult.result)
        if (convertResult !is ContentParseResult.ParsedSuccessfully) return convertResult.of()

        val resolveResult = resolveOpenApi(convertResult.result)
        if (resolveResult !is ContentParseResult.ParsedSuccessfully) return resolveResult.of()

        return ContentParseResult.ParsedSuccessfully(DefaultContext(content, convertResult.result.openAPI, parseResult.result.swagger))
    }

    private fun parseOpenApi(content: String, authorization: String?): ContentParseResult<SwaggerParseResult> {
        val parseOptions = ParseOptions()
        parseOptions.isResolve = true
        // parseOptions.isResolveFully = true // https://github.com/swagger-api/swagger-parser/issues/682

        val authorizationValue = when {
            authorization is String && propagateAuthorizationUrls.isNotEmpty() ->
                mutableListOf(AuthorizationValue("Authorization", authorization, "header") { url ->
                    propagateAuthorizationUrls.any { it.matcher(url.toString()).matches() }
                })
            else -> mutableListOf()
        }

        val parseResult = OpenAPIV3Parser().readContents(content, authorizationValue, parseOptions)
        return if (parseResult.openAPI === null) {
            if (parseResult.messages.isEmpty() || parseResult.messages.contains("attribute openapi is missing")) {
                ContentParseResult.NotApplicable()
            } else {
                ContentParseResult.ParsedWithErrors(parseResult.messages.filterNotNull().map(::errorToViolation))
            }
        } else {
            ContentParseResult.ParsedSuccessfully(parseResult)
        }
    }

    private fun resolveOpenApi(parseResult: SwaggerParseResult): ContentParseResult<SwaggerParseResult> {
        try {
            ResolverFully(true).resolveFully(parseResult.openAPI)
        } catch (e: NullPointerException) {
            log.warn("Failed to fully resolve OpenAPI schema. Error not covered by pre-resolve checks.\n${parseResult.openAPI}", e)
        }
        return ContentParseResult.ParsedSuccessfully(parseResult)
    }

    private fun parseSwagger(content: String): ContentParseResult<SwaggerDeserializationResult> {
        val parseResult = SwaggerParser().readWithInfo(content, true)
        val didParse = parseResult !== null
        val swaggerIsMissing = parseResult.messages.contains("attribute swagger is missing")
        return if (!didParse || swaggerIsMissing) {
            if (parseResult.messages.isEmpty() || swaggerIsMissing) {
                ContentParseResult.NotApplicable()
            } else {
                ContentParseResult.ParsedWithErrors(parseResult.messages.mapNotNull(::errorToViolation))
            }
        } else {
            ContentParseResult.ParsedSuccessfully(parseResult)
        }
    }

    private fun convertSwaggerToOpenAPI(parseResult: SwaggerDeserializationResult): ContentParseResult<SwaggerParseResult> {
        val convertResult = try {
            SwaggerConverter().convert(parseResult)
        } catch (t: Throwable) {
            log.warn(
                "Unable to convert specification from 'Swagger 2' to 'OpenAPI 3'. Error not covered by pre-convert checks.",
                t
            )
            val violation = Violation("Unable to parse specification", JsonPointers.EMPTY)
            return ContentParseResult.ParsedWithErrors(listOf(violation))
        }
        return if (convertResult.openAPI === null) {
            if (convertResult.messages.orEmpty().isNotEmpty()) {
                ContentParseResult.ParsedWithErrors(convertResult.messages.mapNotNull(::errorToViolation))
            } else {
                log.warn("Unable to convert specification from 'Swagger 2' to 'OpenAPI 3'. No error specified, but 'openAPI' is null.")
                val violation = Violation("Unable to parse specification", JsonPointers.EMPTY)
                ContentParseResult.ParsedWithErrors(listOf(violation))
            }
        } else {
            ContentParseResult.ParsedSuccessfully(convertResult)
        }
    }

    private fun errorToViolation(error: String): Violation =
        Violation(error, JsonPointers.EMPTY)
}
