package org.schabi.newpipe.extractor.utils

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

object JavaScript {

    @JvmStatic
    fun compileOrThrow(function: String) {
        Context.enter().use { context ->
            context.isInterpretedMode = true
            context.compileString(function, null, 1, null)
        }
    }

    @JvmStatic
    fun run(function: String, functionName: String, vararg parameters: String): String {
        Context.enter().use { context ->
            context.isInterpretedMode = true
            val scope = context.initSafeStandardObjects()
            context.evaluateString(scope, function, functionName, 1, null)
            val jsFunction = scope.get(functionName, scope) as Function
            val result = jsFunction.call(context, scope, scope, parameters)
            return result.toString()
        }
    }
}
