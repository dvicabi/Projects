package com.feurstagram.patches.shared

import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.Reference

/** Small local compatibility helper; newer public Morphe libraries removed it. */
inline fun <reified T : Reference> Instruction.getReference(): T? =
    (this as? ReferenceInstruction)?.reference as? T
