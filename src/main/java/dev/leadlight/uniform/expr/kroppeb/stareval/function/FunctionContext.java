package dev.leadlight.uniform.expr.kroppeb.stareval.function;

import dev.leadlight.uniform.expr.kroppeb.stareval.expression.Expression;

public interface FunctionContext {
	Expression getVariable(String name);

	boolean hasVariable(String name);
}
