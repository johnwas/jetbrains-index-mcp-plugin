package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for searching code symbols across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindSymbolTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_LIMIT = 25
        private const val MAX_LIMIT = 100
    }

    override val name = ToolNames.FIND_SYMBOL

    override val description = """
        Search for symbols by name across the codebase. Use when you know a symbol name but not its location—finds classes, methods, fields, functions. Faster and more accurate than grep for code navigation.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Matching: substring ("Service" → "UserService") and camelCase ("USvc" → "UserService").

        Returns: matching symbols with qualified names, file paths, line/column numbers, and kind.

        Parameters: query (required), includeLibraries (optional, default: false), limit (optional, default: 25, max: 100).

        Example: {"query": "UserService"} or {"query": "find_user", "includeLibraries": true}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.QUERY, "Search pattern. Supports substring and camelCase matching.", required = true)
        .booleanProperty(ParamNames.INCLUDE_LIBRARIES, "Include symbols from library dependencies. Default: false.")
        .stringProperty(ParamNames.LANGUAGE, "Filter results by language (e.g., \"Kotlin\", \"Java\", \"Python\"). Case-insensitive. Optional.")
        .enumProperty(ParamNames.MATCH_MODE, "How to match the query. Default: \"substring\".", listOf("substring", "prefix", "exact"))
        .intProperty(ParamNames.LIMIT, "Maximum results to return. Default: 25, Max: 100.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments[ParamNames.QUERY]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUERY}")
        val includeLibraries = arguments[ParamNames.INCLUDE_LIBRARIES]?.jsonPrimitive?.boolean ?: false
        val languageFilter = arguments[ParamNames.LANGUAGE]?.jsonPrimitive?.content
        val matchMode = arguments[ParamNames.MATCH_MODE]?.jsonPrimitive?.content ?: "substring"
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            // Aggregate results from ALL available language handlers
            val handlers = LanguageHandlerRegistry.getAllSymbolSearchHandlers()
            if (handlers.isEmpty()) {
                return@suspendingReadAction createErrorResult(
                    "No symbol search handlers available. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForSymbolSearch()}"
                )
            }

            val allMatches = mutableListOf<SymbolMatch>()
            val handlerLimit = limit

            for (handler in handlers) {
                val handlerResults = handler.searchSymbols(project, query, includeLibraries, handlerLimit, matchMode)
                for (symbolData in handlerResults) {
                    if (languageFilter != null && !symbolData.language.equals(languageFilter, ignoreCase = true)) continue
                    allMatches.add(SymbolMatch(
                        name = symbolData.name,
                        qualifiedName = symbolData.qualifiedName,
                        kind = symbolData.kind,
                        file = symbolData.file,
                        line = symbolData.line,
                        column = symbolData.column,
                        containerName = symbolData.containerName,
                        language = symbolData.language
                    ))
                }
            }

            val sortedMatches = allMatches
                .distinctBy { "${it.file}:${it.line}:${it.column}:${it.name}" }
                .take(limit)

            createJsonResult(FindSymbolResult(
                symbols = sortedMatches,
                totalCount = sortedMatches.size,
                query = query
            ))
        }
    }

}
