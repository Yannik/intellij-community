package org.jetbrains.protocolModelGenerator

import org.jetbrains.protocolReader.TextOutput

class TypeDescriptor(val type: BoxableType, private val optional: Boolean, private val nullable: Boolean = false, private val asRawString: Boolean = false) {
  fun isNullable() = nullable

  fun writeAnnotations(out: TextOutput) {
    if (optional || asRawString) {
      out.append("@org.jetbrains.jsonProtocol.JsonField(")
      if (optional) {
        out.append("optional=true")
      }
      if (asRawString) {
        if (optional) {
          out.append(", ")
        }
        out.append("allowAnyPrimitiveValue=true")
      }
      out.append(")").newLine()
    }

    if (isNullable()) {
      out.append("@org.jetbrains.jsonProtocol.JsonNullable").newLine()
    }
  }

  val isNullableType: Boolean
    get() = type != BoxableType.BOOLEAN && type != BoxableType.INT && type != BoxableType.Companion.LONG && (nullable || optional || asRawString)
}
