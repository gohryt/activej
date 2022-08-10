package io.activej.dataflow.calcite.where;

import io.activej.dataflow.calcite.operand.Operand;
import io.activej.record.Record;

import java.util.List;

import static io.activej.dataflow.calcite.utils.Utils.equalsUnknown;

public final class NotEqPredicate implements WherePredicate {
	private final Operand<?> left;
	private final Operand<?> right;

	public NotEqPredicate(Operand<?> left, Operand<?> right) {
		this.left = left;
		this.right = right;
	}

	@Override
	public boolean test(Record record) {
		Object leftValue = left.getValue(record);
		if (leftValue == null) return false;

		Object rightValue = right.getValue(record);
		if (rightValue == null) return false;

		return !equalsUnknown(leftValue, rightValue);
	}

	@Override
	public WherePredicate materialize(List<Object> params) {
		return new NotEqPredicate(
				left.materialize(params),
				right.materialize(params)
		);
	}

	public Operand<?> getLeft() {
		return left;
	}

	public Operand<?> getRight() {
		return right;
	}

	@Override
	public String toString() {
		return "NotEqPredicate[" +
				"left=" + left + ", " +
				"right=" + right + ']';
	}

}
