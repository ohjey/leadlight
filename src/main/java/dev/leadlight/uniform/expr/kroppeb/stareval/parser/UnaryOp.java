package dev.leadlight.uniform.expr.kroppeb.stareval.parser;

public record UnaryOp(String name) {


	@Override
	public String toString() {
		return this.name;
	}
}
