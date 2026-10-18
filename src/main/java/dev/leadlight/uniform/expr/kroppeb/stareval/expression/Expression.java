package dev.leadlight.uniform.expr.kroppeb.stareval.expression;

import dev.leadlight.uniform.expr.kroppeb.stareval.function.FunctionContext;
import dev.leadlight.uniform.expr.kroppeb.stareval.function.FunctionReturn;

import java.util.Collection;

public interface Expression {
	void evaluateTo(FunctionContext context, FunctionReturn functionReturn);

	default Expression partialEval(FunctionContext context, FunctionReturn functionReturn) {
		return this;
	}

	void listVariables(Collection<? super VariableExpression> variables);
}
