package org.jetbrains.protocolModelGenerator

import com.intellij.openapi.util.text.StringUtil
import gnu.trove.THashMap
import org.jetbrains.io.JsonReaderEx
import org.jetbrains.jsonProtocol.*
import org.jetbrains.protocolReader.TextOutput
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.*

fun main(args: Array<String>) {
  val outputDir = args[0]
  val schemaUrl = args[1]
  val bytes: ByteArray
  if (schemaUrl.startsWith("http")) {
    bytes = loadBytes(URL(schemaUrl).openStream())
  }
  else {
    bytes = Files.readAllBytes(FileSystems.getDefault().getPath(schemaUrl))
  }
  val reader = JsonReaderEx(bytes.toString(Charsets.UTF_8))
  reader.isLenient = true
  Generator(outputDir, args[2], args[3], ProtocolSchemaReaderImpl().parseRoot(reader))
}

private fun loadBytes(stream: InputStream): ByteArray {
  val buffer = ByteArrayOutputStream(Math.max(stream.available(), 16 * 1024))
  val bytes = ByteArray(1024 * 20)
  while (true) {
    val n = stream.read(bytes, 0, bytes.size)
    if (n <= 0) {
      break
    }
    buffer.write(bytes, 0, n)
  }
  buffer.close()
  return buffer.toByteArray()
}

internal class Naming(val inputPackage: String, val requestClassName: String) {
  val params = ClassNameScheme.Output("", inputPackage)
  val additionalParam = ClassNameScheme.Output("", inputPackage)
  val outputTypedef: ClassNameScheme = ClassNameScheme.Output("Typedef", inputPackage)

  val commandResult = ClassNameScheme.Input("Result", inputPackage)
  val eventData = ClassNameScheme.Input("EventData", inputPackage)
  val inputValue = ClassNameScheme.Input("Value", inputPackage)
  val inputEnum = ClassNameScheme.Input("", inputPackage)
  val inputTypedef = ClassNameScheme.Input("Typedef", inputPackage)

  val commonTypedef = ClassNameScheme.Common("Typedef", inputPackage)
}

/**
 * Read metamodel and generates set of files with Java classes/interfaces for the protocol.
 */
internal class Generator(outputDir: String, private val rootPackage: String, requestClassName: String, metamodel: ProtocolMetaModel.Root) {
  val jsonProtocolParserClassNames = ArrayList<String>()
  val parserRootInterfaceItems = ArrayList<ParserRootInterfaceItem>()
  val typeMap = TypeMap()

  val nestedTypeMap = THashMap<NamePath, StandaloneType>()

  val fileSet = FileSet(FileSystems.getDefault().getPath(outputDir))
  val naming = Naming(rootPackage, requestClassName)

  init {
    val domainList = metamodel.domains()
    val domainGeneratorMap = THashMap<String, DomainGenerator>()

    for (domain in domainList) {
      if (isDomainSkipped(domain)) {
        System.out.println("Domain skipped: ${domain.domain()}")
        continue
      }

      val fileUpdater = fileSet.createFileUpdater("${StringUtil.nullize(domain.domain()) ?: "protocol"}.kt")
      val out = fileUpdater.out

      out.append("// Generated source").newLine().append("package ").append(getPackageName(rootPackage, domain.domain())).newLine().newLine()
      out.append("import org.jetbrains.jsonProtocol.*").newLine()
      out.append("import org.jetbrains.io.JsonReaderEx").newLine()

      val domainGenerator = DomainGenerator(this, domain, fileUpdater)
      domainGeneratorMap.put(domain.domain(), domainGenerator)
      domainGenerator.registerTypes()

      out.newLine()

      System.out.println("Domain generated: ${domain.domain()}")
    }

    typeMap.domainGeneratorMap = domainGeneratorMap

    for (domainGenerator in domainGeneratorMap.values) {
      domainGenerator.generateCommandsAndEvents()
    }

    val sharedFileUpdater = if (domainGeneratorMap.size == 1) {
      domainGeneratorMap.values.first().fileUpdater
    }
    else {
      val fileUpdater = fileSet.createFileUpdater("protocol.kt")
      val out = fileUpdater.out
      out.append("// Generated source").newLine().append("package ").append(rootPackage).newLine().newLine()
      out.append("import org.jetbrains.jsonProtocol.*").newLine()
      out.append("import org.jetbrains.io.JsonReaderEx").newLine()
      fileUpdater
    }
    typeMap.generateRequestedTypes()
    generateParserInterfaceList(sharedFileUpdater.out)
    generateParserRoot(parserRootInterfaceItems, sharedFileUpdater.out)
    fileSet.deleteOtherFiles()

    for (domainGenerator in domainGeneratorMap.values) {
      domainGenerator.fileUpdater.update()
    }

    if (domainGeneratorMap.size != 1) {
      sharedFileUpdater.update()
    }
  }

  fun resolveType(typedObject: ItemDescriptor, scope: ResolveAndGenerateScope): TypeDescriptor {
    val optional = typedObject is ItemDescriptor.Named && typedObject.optional()
    return switchByType(typedObject, object : TypeVisitor<TypeDescriptor> {
      override fun visitRef(refName: String) = TypeDescriptor(resolveRefType(scope.getDomainName(), refName, scope.getTypeDirection()), optional)

      override fun visitBoolean() = TypeDescriptor(BoxableType.BOOLEAN, optional)

      override fun visitEnum(enumConstants: List<String>): TypeDescriptor {
        assert(scope is MemberScope)
        return TypeDescriptor((scope as MemberScope).generateEnum(typedObject.description(), enumConstants), optional)
      }

      override fun visitString() = TypeDescriptor(BoxableType.STRING, optional)

      override fun visitInteger() = TypeDescriptor(BoxableType.INT, optional)

      override fun visitNumber() = TypeDescriptor(BoxableType.NUMBER, optional)

      override fun visitMap() = TypeDescriptor(BoxableType.MAP, optional)

      override fun visitArray(items: ProtocolMetaModel.ArrayItemType): TypeDescriptor {
        val type = scope.resolveType(items).type
        return TypeDescriptor(ListType(type), optional, false, type == BoxableType.ANY_STRING)
      }

      override fun visitObject(properties: List<ProtocolMetaModel.ObjectProperty>?) = TypeDescriptor(scope.generateNestedObject(typedObject.description(), properties), optional)

      override fun visitUnknown() = TypeDescriptor(BoxableType.STRING, optional, false, true)
    })
  }

  private fun generateParserInterfaceList(out: TextOutput) {
    // write classes in stable order
    Collections.sort(jsonProtocolParserClassNames)

    out.newLine().newLine().append("val PARSER_CLASSES = arrayOf(").newLine()
    for (name in jsonProtocolParserClassNames) {
      out.append("  ").append(name).append("::class.java")
      if (name != jsonProtocolParserClassNames.last()) {
        out.append(',')
      }
      out.newLine()
    }
    out.append(')')
  }

  private fun generateParserRoot(parserRootInterfaceItems: List<ParserRootInterfaceItem>, out: TextOutput) {
    // write classes in stable order
    Collections.sort<ParserRootInterfaceItem>(parserRootInterfaceItems)

    out.newLine().newLine().append("interface ").append(READER_INTERFACE_NAME).append(" : org.jetbrains.jsonProtocol.ResponseResultReader").openBlock()
    for (item in parserRootInterfaceItems) {
      item.writeCode(out)
      out.newLine()
    }
    out.append("override fun readResult(methodName: String, reader: org.jetbrains.io.JsonReaderEx): Any? = ")
    out.append("when (methodName)").block {
      for (item in parserRootInterfaceItems) {
        out.append('"')
        if (!item.domain.isEmpty()) {
          out.append(item.domain).append('.')
        }
        out.append(item.name).append('"').append(" -> ")
        item.appendReadMethodName(out)
        out.append("(reader)").newLine()
      }
      out.append("else -> null")
    }

    out.closeBlock()
  }

  /**
   * Resolve absolute (DOMAIN.TYPE) or relative (TYPE) type name
   */
  private fun resolveRefType(scopeDomainName: String, refName: String, direction: TypeData.Direction): BoxableType {
    val pos = refName.indexOf('.')
    val domainName: String
    val shortName: String
    if (pos == -1) {
      domainName = scopeDomainName
      shortName = refName
    }
    else {
      domainName = refName.substring(0, pos)
      shortName = refName.substring(pos + 1)
    }
    return typeMap.resolve(domainName, shortName, direction)!!
  }

//  fun startJavaFile(nameScheme: ClassNameScheme, domain: ProtocolMetaModel.Domain, baseName: String): FileUpdater {
//    return startJavaFile(nameScheme.getPackageNameVirtual(domain.domain()), nameScheme.getShortName(baseName) + ".java")
//  }
}

val READER_INTERFACE_NAME = "ProtocolResponseReader"

private fun isDomainSkipped(domain: ProtocolMetaModel.Domain): Boolean {
  if (domain.domain() == "CSS" || domain.domain() == "Inspector") {
    return false
  }

  // todo DOMDebugger
  return domain.hidden() || domain.domain() == "DOMDebugger" || domain.domain() == "Timeline" || domain.domain() == "Input"
}

fun generateMethodNameSubstitute(originalName: String, out: TextOutput): String {
  if (originalName != "this") {
    return originalName
  }
  out.append("@org.jetbrains.jsonProtocol.JsonField(name = \"").append(originalName).append("\")").newLine()
  return "get${Character.toUpperCase(originalName.get(0))}${originalName.substring(1)}"
}

fun capitalizeFirstChar(s: String): String {
  if (!s.isEmpty() && Character.isLowerCase(s.get(0))) {
    return Character.toUpperCase(s.get(0)) + s.substring(1)
  }
  return s
}

fun <R> switchByType(typedObject: ItemDescriptor, visitor: TypeVisitor<R>): R {
  val refName = if (typedObject is ItemDescriptor.Referenceable) typedObject.ref() else null
  if (refName != null) {
    return visitor.visitRef(refName)
  }
  val typeName = typedObject.type()
  when (typeName) {
    BOOLEAN_TYPE -> return visitor.visitBoolean()
    STRING_TYPE -> {
      if (typedObject.getEnum() != null) {
        return visitor.visitEnum(typedObject.getEnum()!!)
      }
      return visitor.visitString()
    }
    INTEGER_TYPE, "int" -> return visitor.visitInteger()
    NUMBER_TYPE -> return visitor.visitNumber()
    ARRAY_TYPE -> return visitor.visitArray(typedObject.items()!!)
    OBJECT_TYPE -> {
      if (typedObject !is ItemDescriptor.Type) {
        return visitor.visitObject(null)
      }

      val properties = typedObject.properties()
      if (properties == null || properties.isEmpty()) {
        return visitor.visitMap()
      }
      else {
        return visitor.visitObject(properties)
      }
    }
    ANY_TYPE -> return visitor.visitUnknown()
    UNKNOWN_TYPE -> return visitor.visitUnknown()
  }
  throw RuntimeException("Unrecognized type " + typeName)
}