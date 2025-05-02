package com.yandex.yatagan.intellij.data

import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.Type

class AnalyzedAnnotation(
    val shortRepr: String,
    val fullRepr: String,
) {
    constructor(annotation: Annotation) : this(
        shortRepr = ToShortString.visitAnnotation(annotation),
        fullRepr = annotation.toString(),
    )

    private object ToShortString : Annotation.Value.Visitor<String> {
        override fun visitDefault(value: Any?) = throw AssertionError()
        override fun visitBoolean(value: Boolean) = value.toString()
        override fun visitByte(value: Byte) = value.toString()
        override fun visitShort(value: Short) = value.toString()
        override fun visitInt(value: Int) = value.toString()
        override fun visitLong(value: Long) = value.toString()
        override fun visitChar(value: Char) = value.toString()
        override fun visitFloat(value: Float) = value.toString()
        override fun visitDouble(value: Double) = value.toString()
        override fun visitString(value: String) = value
        override fun visitType(value: Type) = "$value"
        override fun visitAnnotation(value: Annotation) = buildString {
            append('@')
            append(value.annotationClass.qualifiedName.substringAfterLast('.'))
            val attributes = value.annotationClass.attributes
            if (attributes.any()) {
                attributes
                    .sortedBy { it.name }
                    .joinTo(this, prefix = "(", postfix = ")", separator = ",") {
                        value.getValue(it).accept(ToShortString)
                    }
            }
        }

        override fun visitEnumConstant(enum: Type, constant: String) = constant
        override fun visitUnresolved(): String = "???"
        override fun visitArray(value: List<Annotation.Value>): String {
            return value.joinToString(prefix = "[", postfix = "]", separator = ",") { it.accept(ToShortString) }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AnalyzedAnnotation

        return fullRepr == other.fullRepr
    }

    override fun hashCode(): Int {
        return fullRepr.hashCode()
    }
}