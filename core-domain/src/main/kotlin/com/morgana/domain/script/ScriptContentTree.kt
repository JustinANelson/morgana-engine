package com.morgana.domain.script

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Turns a serializable content definition into a plain tree for scripts: `Map<String, Any?>`, `List<Any?>`, numbers,
 * `String` and `Boolean`; enums become their names. Defaults are written out, so a script sees `hitStopTicks = 0`
 * rather than nil, and null fields are left out, so an absent optional field is nil.
 *
 * It encodes with the content type's own serializer (no JSON library), so a new field shows up in scripts by itself.
 */
@OptIn(ExperimentalSerializationApi::class)
object ScriptContentTree {
    fun <T> of(serializer: KSerializer<T>, value: T): Any? {
        val root = Root()
        serializer.serialize(root, value)
        return root.result
    }

    private abstract class Node : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()

        /** Stores the value of the element being encoded. */
        abstract fun put(value: Any?)

        override fun encodeValue(value: Any) = put(value)
        override fun encodeNull() = put(null)
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = put(enumDescriptor.getElementName(index))

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            val child: Container = when (descriptor.kind) {
                StructureKind.LIST -> ListNode()
                StructureKind.MAP -> MapNode()
                else -> ClassNode()
            }
            // The child's own container is stored now and filled in as it encodes.
            put(child.container)
            return child
        }

        override fun endStructure(descriptor: SerialDescriptor) {}
    }

    private abstract class Container : Node() {
        abstract val container: Any
    }

    private class Root : Node() {
        var result: Any? = null
        override fun put(value: Any?) { result = value }
    }

    private class ClassNode : Container() {
        private val map = linkedMapOf<String, Any?>()
        private var key = ""
        override val container: Any get() = map
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            key = descriptor.getElementName(index)
            return true
        }
        override fun put(value: Any?) { if (value != null) map[key] = value }
    }

    private class ListNode : Container() {
        private val list = mutableListOf<Any?>()
        override val container: Any get() = list
        override fun encodeElement(descriptor: SerialDescriptor, index: Int) = true
        override fun put(value: Any?) { list.add(value) }
    }

    private class MapNode : Container() {
        private val map = linkedMapOf<String, Any?>()
        private var key = ""
        private var isKey = true
        override val container: Any get() = map
        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            isKey = index % 2 == 0
            return true
        }
        override fun put(value: Any?) {
            if (isKey) key = value.toString() else if (value != null) map[key] = value
        }
    }
}
