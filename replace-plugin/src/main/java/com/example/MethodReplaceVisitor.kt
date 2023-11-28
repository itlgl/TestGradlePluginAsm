package com.example

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class MethodReplaceVisitor(
    api: Int,
    classVisitor: ClassVisitor,
    val oldOwner: String,
    val oldMethodName: String,
    val oldMethodDesc: String,
    val newOwner: String,
    val newMethodName: String,
    val newMethodDesc: String,
    val newOpcode: Int
) : ClassVisitor(api, classVisitor) {

    var printMethod = false

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        var mv: MethodVisitor? = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (printMethod) {
            println("ReplaceTransform: visitMethod access:$access, name:$name, $descriptor, signature:$signature")
        }
        if (mv != null && name != "<init>" && name != "<clinit>") {
            val isAbstractMethod = (access and Opcodes.ACC_ABSTRACT) != 0
            val isNativeMethod = (access and Opcodes.ACC_NATIVE) != 0
            if (!isAbstractMethod && !isNativeMethod) {
                mv = MethodReplaceAdapter(api, mv)
            }
        }
        return mv
    }

    inner class MethodReplaceAdapter(api: Int, methodVisitor: MethodVisitor) :
        MethodVisitor(api, methodVisitor) {

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            if (printMethod) {
                println("ReplaceTransform: visitMethodInsn, owner: $owner, name: $name, descriptor: $descriptor")
            }
            if (oldOwner == owner && oldMethodName == name && oldMethodDesc == descriptor) {
                super.visitMethodInsn(
                    newOpcode,
                    newOwner,
                    newMethodName,
                    newMethodDesc,
                    false
                )
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }
    }
}