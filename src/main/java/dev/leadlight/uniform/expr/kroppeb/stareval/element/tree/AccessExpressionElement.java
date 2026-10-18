package dev.leadlight.uniform.expr.kroppeb.stareval.element.tree;

import dev.leadlight.uniform.expr.kroppeb.stareval.element.AccessibleExpressionElement;

public record AccessExpressionElement(AccessibleExpressionElement base,
									  String index) implements AccessibleExpressionElement {


	@Override
	public String toString() {
		return "Access{" + this.base + "[" + this.index + "]}";
	}
}
