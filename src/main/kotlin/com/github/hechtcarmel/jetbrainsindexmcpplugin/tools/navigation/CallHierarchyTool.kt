package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool for analyzing method call relationships across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class CallHierarchyTool : AbstractMcpTool() {

    override val name = "ide_call_hierarchy"

    override val description = """
        Build a call hierarchy tree for a method/function. Use to trace execution flow—find what calls this method (callers) or what this method calls (callees).

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Rust note: "callers" direction works well; "callees" direction may have limited results due to Rust plugin PSI resolution constraints.

        Returns: recursive tree with method signatures, file locations (line/column), and nested call relationships.

        Parameters: file + line + column + direction (required). direction: "callers" or "callees". depth (optional, default: 3, max: 5).

        Example: {"file": "src/Service.java", "line": 42, "column": 10, "direction": "callers"}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .lineAndColumn()
        .enumProperty("direction", "Direction: 'callers' (methods that call this method) or 'callees' (methods this method calls)", listOf("callers", "callees"), required = true)
        .intProperty("depth", "How many levels deep to traverse the call hierarchy (default: 3, max: 5)")
        .build()

    companion object {
        private const val DEFAULT_DEPTH = 3
        private const val MAX_DEPTH = 5
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: line")
        val column = arguments["column"]?.jsonPrimitive?.int
            ?: return createErrorResult("Missing required parameter: column")
        val direction = arguments["direction"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: direction")
        val depth = (arguments["depth"]?.jsonPrimitive?.int ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH)

        if (direction !in listOf("callers", "callees")) {
            return createErrorResult("direction must be 'callers' or 'callees'")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            ProgressManager.checkCanceled() // Allow cancellation

            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at position $file:$line:$column")

            // Find appropriate handler for this element's language
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
            if (handler == null) {
                return@suspendingReadAction createErrorResult(
                    "No call hierarchy handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForCallHierarchy()}"
                )
            }

            ProgressManager.checkCanceled() // Allow cancellation before heavy operation

            val hierarchyData = handler.getCallHierarchy(element, project, direction, depth)
            if (hierarchyData == null) {
                return@suspendingReadAction createErrorResult("No method/function found at position")
            }

            // Convert handler result to tool result
            createJsonResult(CallHierarchyResult(
                element = convertToCallElement(hierarchyData.element),
                calls = hierarchyData.calls.map { convertToCallElement(it) }
            ))
        }
    }

    /**
     * Converts handler CallElementData to tool CallElement.
     */
    private fun convertToCallElement(data: CallElementData): CallElement {
        return CallElement(
            name = data.name,
            file = data.file,
            line = data.line,
            column = data.column,
            language = data.language,
            children = data.children?.map { convertToCallElement(it) }
        )
    }
}
