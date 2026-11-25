package com.feurstagram.patches.signature

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

/**
 * Instagram wraps a signing certificate's SHA-256 in a "key hash" (Base64, 43 chars) via
 * X.3uq, keeps Meta's trusted key hashes in a static allowlist in X.1eX, and exposes two
 * membership checks:
 *
 *   - X.1eX.A01(keyHash)          -> keyHash in allowlist
 *   - X.1eY.A01(keyHash, ref, z)  -> family-app scope trust (with the same allowlist fallback)
 *
 * Instagram 446 reshaped the second one into an instance method on the trusted-provider class
 * that takes the caller identity and reads the key hashes from it. Only this modern
 * shape is supported by this optional compatibility patch.
 *
 * Re-signing the APK produces a key hash that is not in the allowlist, so both checks fail,
 * X.0XS.A02 (the "is this APK signed by Meta" gate) turns false, and Instagram's deep-link
 * dispatcher silently drops navigation to the linked content (issue #36).
 *
 * Both methods take the X.3uq key-hash type, so they are matched structurally after locating
 * that type by its stable error string. The obfuscated class/method names therefore never
 * appear in this patch and it survives Instagram renaming them between releases.
 */
private const val SIGNATURE_TRUST = "Lcom/feurstagram/extension/SignatureTrust;"

internal object KeyHashClassFingerprint : Fingerprint(
    strings = listOf("Invalid SHA256 key hash"),
)

@Suppress("unused")
val signatureCheckBypassPatch = bytecodePatch(
    name = "Own signing-key compatibility",
    description = "Optional deep-link compatibility for this app's own signing key only. " +
        "Other callers keep Instagram's original checks; unsupported legacy checks fail the build.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val keyHashType = KeyHashClassFingerprint.classDef.type

        // Never restore the old unconditional trust bypass. Refuse that APK
        // shape before modifying any trust check.
        val legacyScopeCheck = Fingerprint(
            returnType = "Z",
            parameters = listOf(keyHashType, keyHashType, "Z"),
            custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
        ).methodOrNull
        if (legacyScopeCheck != null) {
            throw PatchException("Legacy signature scope is unsupported; unconditional trust is prohibited")
        }

        // Only one method with signature boolean(X.3uq): X.1eX.A01, the allowlist membership
        // check powering X.0XS.A02 (the self-signature gate) and every other trust decision.
        // Only our own key is added: every other app keeps Instagram's answer. Trusting
        // every key made the "different key" intent scope drop the Android share sheet
        // as if it were a Meta app, so "Share externally" did nothing (issue #182).
        Fingerprint(
            returnType = "Z",
            parameters = listOf(keyHashType),
            custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
        ).method.apply {
            if (implementation!!.registerCount < 2) throw PatchException("Signature allowlist check has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $SIGNATURE_TRUST->isOwnKeyHash(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :instagram
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("instagram", getInstruction(0)),
            )
        }

        // From Instagram 446 it became an instance method boolean(CallerIdentity, Z) on the
        // trusted-provider class, which reads both key hashes from the caller identity
        // (a getter on its first parameter) instead of taking them as parameters. That shape
        // is unique in the APK; the class's own "*|all_packages|*" string is not, so it is
        // matched on structure alone.
        val scopeCheck = Fingerprint(
            returnType = "Z",
            custom = { method, _ ->
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[1].toString() == "Z" &&
                    method.readsKeyHashFrom(method.parameterTypes[0].toString(), keyHashType)
            },
        ).method
        // Same rule as the allowlist: a caller signed with our key is trusted, every
        // other app gets Instagram's answer. The third-party intent scope behind the
        // share sheet asks this about each target (issue #182).
        val identity = scopeCheck.parameterTypes[0].toString()
        val keyHashGetter = scopeCheck.instructions.firstNotNullOf { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                it.returnType == keyHashType && it.definingClass == identity && it.parameterTypes.isEmpty()
            }
        }
        scopeCheck.apply {
            if (implementation!!.registerCount < 4) throw PatchException("Signature scope check has no free register")
            addInstructionsWithLabels(
                0,
                """
                    if-eqz p1, :instagram
                    invoke-virtual { p1 }, $identity->${keyHashGetter.name}()$keyHashType
                    move-result-object v0
                    invoke-static { v0 }, $SIGNATURE_TRUST->isOwnKeyHash(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :instagram
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("instagram", getInstruction(0)),
            )
        }
    }
}

/** True if the method calls a getter declared on [ownerType] that returns the key-hash type. */
private fun Method.readsKeyHashFrom(ownerType: String, keyHashType: String): Boolean =
    implementation?.instructions?.any { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference != null && reference.returnType == keyHashType && reference.definingClass == ownerType
    } ?: false
