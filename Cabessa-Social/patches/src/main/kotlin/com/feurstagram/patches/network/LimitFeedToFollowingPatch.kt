package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import com.feurstagram.patches.shared.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val LIMIT_FEED_CLASS = "Lcom/feurstagram/extension/LimitFeed;"

// The main-feed request object. Its debug toString() embeds these literals, so
// the class is matched by stable wire/debug strings rather than its obfuscated
// LX/ name.
internal object MainFeedRequestClassFingerprint : Fingerprint(
    strings = listOf("Request{mReason=", ", mInstanceNumber="),
    // 456 also has an unrelated request with the same toString text. Only the
    // timeline request carries parameter maps.
    custom = { method, classDef ->
        method.name == "toString" && classDef.fields.any { it.type == "Ljava/util/Map;" }
    },
)

// The constructor of that request class, located via the class fingerprint
// above — never by an obfuscated name.
internal object InitMainFeedRequestFingerprint : Fingerprint(
    name = "<init>",
    classFingerprint = MainFeedRequestClassFingerprint,
)

// A method that assembles the feed-request headers. Used only to discover which
// of the request's (obfuscated-named) Map fields holds the headers, by finding
// the Map field that belongs to the request class.
internal object MainFeedHeaderMapFinderFingerprint : Fingerprint(
    strings = listOf("pagination_source", "FEED_REQUEST_SENT"),
)

// Both streaming and ordinary /feed/timeline/ builders call this method in
// the inspected 456 APK. It writes max_id and then merges request parameters
// and extra maps, which could otherwise replace pagination_source later.
internal object MainFeedParameterBuilderFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("is_feed_eager_refresh", "X-Ig-Tail-Load-Request", "client_cache_top_media_id"),
)

@Suppress("unused")
val limitFeedToFollowingPatch = bytecodePatch(
    name = "Limit feed to following profiles",
    description = "Forces the main-feed request header to Following on every request. " +
        "The server continuation metadata and native chronological paging are retained.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        // Resolve the exact Map used by Map.get("pagination_source"). The
        // request contains several maps; their declaration/read order is not
        // sufficient evidence that a map chooses the feed mode.
        val requestClass = MainFeedRequestClassFingerprint.classDef.type
        val headerField = MainFeedHeaderMapFinderFingerprint.method.run {
            val key = instructions.single { it.getReference<StringReference>()?.string == "pagination_source" }
            val keyRegister = (key as OneRegisterInstruction).registerA
            val lookup = instructions[key.location.index + 1]
            val lookupReference = lookup.getReference<MethodReference>()
            if (lookup.opcode != Opcode.INVOKE_INTERFACE || lookupReference?.definingClass != "Ljava/util/Map;" ||
                lookupReference.name != "get" || (lookup as FiveRegisterInstruction).registerD != keyRegister) {
                throw PatchException("Following pagination map lookup changed")
            }
            val load = instructions[key.location.index - 1]
            val field = load.getReference<FieldReference>()
            if (load.opcode != Opcode.IGET_OBJECT || field?.type != "Ljava/util/Map;" ||
                field.definingClass != requestClass || (load as TwoRegisterInstruction).registerA != lookup.registerC) {
                throw PatchException("Following request parameter map changed")
            }
            field
        }

        // In the constructor, wrap the value stored into that header field with
        // our rewriter, just before the store.
        InitMainFeedRequestFingerprint.method.apply {
            val store = instructions.single {
                it.opcode == Opcode.IPUT_OBJECT &&
                    it.getReference<FieldReference>()?.let { field ->
                        field.name == headerField.name && field.type == headerField.type &&
                            field.definingClass == headerField.definingClass
                    } == true
            }
            val headerRegister = (store as TwoRegisterInstruction).registerA

            addInstructions(
                store.location.index,
                "invoke-static/range { v$headerRegister .. v$headerRegister }, " +
                    "$LIMIT_FEED_CLASS->setFollowingHeader(Ljava/util/Map;)Ljava/util/Map;\n" +
                    "move-result-object v$headerRegister",
            )
        }

        // Guard the value at the final native setters too. These setters are
        // found from max_id's call, not by obfuscated names. Other keys and
        // values (including the opaque continuation cursor) pass through.
        MainFeedParameterBuilderFingerprint.method.apply {
            val maxId = instructions.single { it.getReference<StringReference>()?.string == "max_id" }
            val setter = instructions[maxId.location.index + 1]
            val reference = setter.getReference<MethodReference>()
                ?: throw PatchException("Timeline parameter setter missing")
            if (setter.opcode != Opcode.INVOKE_VIRTUAL || reference.returnType != "V" ||
                reference.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;", "Ljava/lang/String;")) {
                throw PatchException("Timeline parameter setter changed")
            }
            val setters = instructions.filter {
                val called = it.getReference<MethodReference>()
                it.opcode == Opcode.INVOKE_VIRTUAL && called?.definingClass == reference.definingClass &&
                    called.returnType == "V" && called.parameterTypes.map { type -> type.toString() } ==
                    listOf("Ljava/lang/String;", "Ljava/lang/String;")
            }
            if (setters.isEmpty()) throw PatchException("Timeline parameter writes missing")
            setters.reversed().forEach { call ->
                val registers = call as FiveRegisterInstruction
                val key = registers.registerD
                val value = registers.registerE
                if (key == value) throw PatchException("Timeline parameter key/value registers alias")
                addInstructions(call.location.index, """
                    invoke-static { v$key, v$value }, $LIMIT_FEED_CLASS->followingParameter(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$value
                """.trimIndent())
            }
        }
    }
}
