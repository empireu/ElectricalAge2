package org.eln2.mc

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

fun noop(){}

@Suppress("FunctionName")
fun DEBUGGER_BREAK(){
    noop()
}

private fun defaultHandleInvalid(property: KProperty1<*, *>) {
    error("Invalid field $property")
}

/**
 * Scans the target class [I] for fields annotated with [FA], and creates a list of [FieldReader]s, caching the result in [target].
 * The field types must be subclasses of [superK]
 * */
fun <FA : Annotation, I : Any> fieldScan(
    inst: Class<I>,
    superK: KClass<*>,
    annotC: Class<FA>,
    target: ConcurrentHashMap<Class<*>, List<FieldInfo<I>>>,
    handleInvalids: (KProperty1<I, *>) -> Unit = ::defaultHandleInvalid
): List<FieldInfo<I>> {
    return target.getOrPut(inst) {
        val accessors = mutableListOf<FieldInfo<I>>()

        inst.kotlin
            .memberProperties
            .filter { it.javaField?.isAnnotationPresent(annotC) ?: false }
            .forEach { property ->
                if (!(property.returnType.classifier as KClass<*>).isSubclassOf(superK)) {
                    handleInvalids(property)
                }
                else {
                    val getProperty = property::get

                    accessors.add(FieldInfo(property.javaField!!) {
                        getProperty(it)
                    })
                }
            }

        accessors
    }
}

data class FieldInfo<I : Any>(
    val field: Field,
    val reader: FieldReader<I>
)

fun interface FieldReader<I : Any> {
    fun get(inst: I): Any?
}

private val kClassId = HashMap<KClass<*>, Int>()
private val classId = HashMap<Class<*>, Int>()

val KClass<*>.reflectId: Int
    get() = synchronized(kClassId) {
        kClassId.getOrPut(this) {
            val result = (this.qualifiedName ?: error("k Failed to get name of $this")).hashCode()

            if (kClassId.values.any { it == result }) {
                error("k reflect ID collision $this")
            }

            result
        }
    }

val Class<*>.reflectId: Int
    get() = synchronized(classId) {
        classId.getOrPut(this) {
            val result = (this.canonicalName ?: error("Failed to get name of $this")).hashCode()

            if (classId.values.any { it == result }) {
                error("reflect ID collision $this")
            }

            result
        }
    }

fun interface ServiceProvider<T> {
    fun getInstance(): T
}

fun interface ExternalResolver {
    fun resolve(c: Class<*>): Any?
}
