package com.feurstagram.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val BLOCK_CLASS = "Lcom/feurstagram/extension/Block;"

// Instagram's home-feed response is a list of items, each wrapped as a JSON
// object keyed by its type (e.g. {"clips_netego": {...}}). The deserialiser
// dispatches on that key. Ads and "suggested" units injected *inline* into the
// timeline payload therefore never hit a separate URL, so the network-layer
// Block cannot see them — only this parse-layer hook can.
//
// The fingerprint anchors on the server-protocol type tokens (stable wire
// strings, not obfuscated code names) plus the non-obfuscated method name
// fragment "parseFromJson", so it survives Instagram renaming the class/method
// to LX/ hex between releases.
//
// Keep this list *short*. Every token is a hard requirement, so one unit type
// Instagram retires takes the whole patch down with it: "in_feed_survey" was
// dropped from the parser in Instagram 444 and silently killed all inline
// ad/netego filtering (issue #117). These four are the load-bearing ones — the
// regular post wrapper, the two netego families and the ad unit — and the method
// name keeps it apart from the serialiser, which carries the same tokens.
internal object FeedItemParseFromJsonFingerprint : Fingerprint(
    strings = listOf(
        "media_or_ad",
        "clips_netego",
        "stories_netego",
        "ad4ad",
    ),
    custom = { method, _ -> method.name.lowercase().contains("parsefromjson") },
)

private const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"

// Native Media accessors retain their wire-field strings even when their method
// names change. The inspected 456 APK uses product_type="clips" for Reels; using
// media_type=video instead would also suppress Stories and ordinary DM videos.
private fun mediaGetter(field: String, result: String) = Fingerprint(
    definingClass = MEDIA_TYPE,
    parameters = emptyList(),
    returnType = result,
    strings = listOf(field),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

private val mediaProductGetterFingerprint = mediaGetter("product_type", "Ljava/lang/String;")
private val mediaVideoVersionsGetterFingerprint = mediaGetter("video_versions", "Ljava/util/List;")
private val mediaVideoManifestGetterFingerprint = mediaGetter("video_dash_manifest", "Ljava/lang/String;")
private val mediaLocalVideoGetterFingerprint = mediaGetter("local_video_path", "Ljava/lang/String;")

// Instagram's media deserialiser. A sponsored post reaches the feed wrapped in
// the same "media_or_ad" unit as an organic one, so the feed-item hook alone
// cannot tell them apart: only the media's own fields can. Its two error-report
// strings are unique to it; the per-field dispatcher it calls (hash-only, no
// strings, field numbers reshuffled every release) is found from inside it,
// and calling it is also what singles the entry point out from helpers that
// share the two strings (Instagram 450 had eleven of them).
internal object MediaParseFromJsonFingerprint : Fingerprint(
    returnType = MEDIA_TYPE,
    strings = listOf(
        "Media:null_pointer_exception",
        "Media:array_out_of_bounds_exception",
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { it.isMediaFieldDispatch() } == true
    },
)

// Instagram 452 moved field dispatch into a codec called by the error-reporting
// wrapper. Follow that call by its signature instead of an obfuscated name.
internal object MediaParseWrapperFingerprint : Fingerprint(
    returnType = MEDIA_TYPE,
    strings = listOf("Media:null_pointer_exception", "Media:array_out_of_bounds_exception"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { it.isMediaCodecCall(method) } == true
    },
)

// Instagram 453 folded the codec back into the parser itself: the (parser, Z)
// entry point reads each field name and hashes it inline, and calls itself for
// carousel children. The hash call is what tells it apart from the other
// helpers sharing the two error strings.
internal object MediaParseInlineFingerprint : Fingerprint(
    returnType = MEDIA_TYPE,
    strings = listOf("Media:null_pointer_exception", "Media:array_out_of_bounds_exception"),
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.parameterTypes[1].toString() == "Z" &&
            method.implementation?.instructions?.any { it.isStringHash() } == true
    },
)

private fun Instruction.isStringHash(): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return opcode == Opcode.INVOKE_VIRTUAL && reference.definingClass == "Ljava/lang/String;" &&
        reference.name == "hashCode"
}

private fun Instruction.isMediaCodecCall(wrapper: Method): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val reference = (this as ReferenceInstruction).reference as MethodReference
    val parserType = wrapper.parameterTypes.firstOrNull()?.toString() ?: return false
    return reference.parameterTypes.map { it.toString() } == listOf(
        "Lcom/instagram/common/session/UserSession;", parserType, "Z",
    ) && reference.returnType.startsWith("L")
}

/** Both codec paths read the field name just before its hash dispatch. */
private fun MutableMethod.hookMediaFieldHash() {
    val hash = instructions.firstOrNull { it.isStringHash() } ?: throw PatchException("Media codec has no field-name hash dispatch")
    val keyRegister = (hash as FiveRegisterInstruction).registerC
    val keyLoad = instructions.take(hash.location.index).lastOrNull {
        it.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (it as OneRegisterInstruction).registerA == keyRegister
    } ?: throw PatchException("Media codec field-name load changed")
    // Instagram 454 null-checks the name and reads the next token in between:
    // allow a few instructions, as long as none of them overwrites the name.
    val between = instructions.subList(keyLoad.location.index + 1, hash.location.index)
    if (between.size > 6) {
        throw PatchException("Media codec field-name load is too far from hash dispatch")
    }
    if (between.any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == keyRegister }) {
        throw PatchException("Media codec field name is overwritten before its hash dispatch")
    }
    addInstructions(
        keyLoad.location.index + 1,
        "invoke-static/range { v$keyRegister .. v$keyRegister }, " +
            "$BLOCK_CLASS->onMediaField(Ljava/lang/String;)V",
    )
}

/** The call that hands one field name to the per-field dispatcher: static (parser, String, Map)V. */
private fun Instruction.isMediaFieldDispatch(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val reference = (this as ReferenceInstruction).reference as MethodReference
    return reference.returnType == "V" &&
        reference.parameterTypes.size == 3 &&
        reference.parameterTypes[1].toString() == "Ljava/lang/String;" &&
        reference.parameterTypes[2].toString() == "Ljava/util/Map;"
}

/** A static call returning a Media, i.e. a feed unit's media payload being parsed. */
private fun Instruction.isMediaParse(): Boolean =
    opcode == Opcode.INVOKE_STATIC &&
        ((this as ReferenceInstruction).reference as MethodReference).returnType == MEDIA_TYPE

@Suppress("unused")
val feedItemFilterPatch = bytecodePatch(
    name = "Feed item filtering",
    description = "Drops sponsored posts, ad/promo and suggested feed units at the JSON-parse " +
        "layer. Removes clips-marked feed media and suppresses their native video sources " +
        "across profiles and shared media, preserving other media product types.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        val productGetter = mediaProductGetterFingerprint.method
        mutableClassDefBy(BLOCK_CLASS).apply {
            val stub = methods.single { it.name == "mediaProductType" }
            methods.remove(stub)
            addBridge("mediaProductType", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 2, """
                instance-of v0, p0, $MEDIA_TYPE
                if-eqz v0, :not_media
                check-cast p0, $MEDIA_TYPE
                invoke-virtual/range { p0 .. p0 }, $MEDIA_TYPE->${productGetter.name}()Ljava/lang/String;
                move-result-object v0
                return-object v0
                :not_media
                const/4 v0, 0x0
                return-object v0
            """, stub.accessFlags)
        }

        // Snapshot the original native getter BEFORE its public entry is guarded.
        // This preserves lazy Pando decoding as well as the ordinary cached list.
        // Only our one-item message player calls this separately named copy;
        // every existing Instagram caller keeps the guarded original getter.
        val videoVersions = mediaVideoVersionsGetterFingerprint.method
        val isolatedName = "cabessaSingleVideoVersions"
        val mediaClass = mutableClassDefBy(MEDIA_TYPE)
        if (mediaClass.methods.any { it.name == isolatedName }) {
            throw PatchException("Single-video media bridge already exists")
        }
        val isolatedVersions = ImmutableMethod.of(videoVersions).toMutable().apply { name = isolatedName }
        mediaClass.methods.add(isolatedVersions)
        val versionType = "Lcom/instagram/api/schemas/VideoVersionIntf;"
        if (classDefBy(versionType).methods.none {
                it.name == "getUrl" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            }) {
            throw PatchException("Native video-version URL contract changed")
        }
        mutableClassDefBy("Lcom/feurstagram/extension/SingleReelPlayer;").apply {
            val stub = methods.single { it.name == "mediaVideoUrl" }
            methods.remove(stub)
            addBridge("mediaVideoUrl", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 5, """
                instance-of v0, p0, $MEDIA_TYPE
                if-eqz v0, :no_single_video
                check-cast p0, $MEDIA_TYPE
                invoke-virtual/range { p0 .. p0 }, $MEDIA_TYPE->$isolatedName()Ljava/util/List;
                move-result-object v0
                if-eqz v0, :no_single_video
                invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v1
                :next_single_version
                invoke-interface { v1 }, Ljava/util/Iterator;->hasNext()Z
                move-result v0
                if-eqz v0, :no_single_video
                invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v2
                instance-of v0, v2, $versionType
                if-eqz v0, :next_single_version
                check-cast v2, $versionType
                invoke-interface { v2 }, $versionType->getUrl()Ljava/lang/String;
                move-result-object v3
                invoke-static { v3 }, Lcom/feurstagram/extension/SingleReelSource;->authorizedCdnUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v3
                if-eqz v3, :next_single_version
                return-object v3
                :no_single_video
                const/4 v0, 0x0
                return-object v0
            """, stub.accessFlags)
        }

        // These three getters already return null for absent data in the target
        // APK. Deny only clips-marked media; ordinary video/Stories take the
        // unmodified native path. Cover local paths as well as network sources so
        // an already cached Reel cannot bypass just an endpoint-level block.
        listOf(mediaVideoVersionsGetterFingerprint, mediaVideoManifestGetterFingerprint,
            mediaLocalVideoGetterFingerprint).forEach { fingerprint ->
            fingerprint.method.apply {
                if (implementation!!.registerCount < 2) {
                    throw PatchException("Media source getter has no local register")
                }
                addInstructions(0, """
                    invoke-static/range { p0 .. p0 }, $BLOCK_CLASS->isReelMedia(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :social_allowed_media
                    const/4 v0, 0x0
                    return-object v0
                    :social_allowed_media
                    nop
                """.trimIndent())
            }
        }

        FeedItemParseFromJsonFingerprint.method.apply {
            // The parse loop reads each item's JSON field name into a register,
            // then dispatches on String.hashCode() through a sparse-switch whose
            // first case loads a type token via const-string/jumbo. The field
            // name is the last value materialised (move-result-object) before
            // that first jumbo string — found structurally, not by name.
            val firstJumboIndex = instructions.first {
                it.opcode == Opcode.CONST_STRING_JUMBO
            }.location.index

            val keyLoad = instructions.last {
                it.opcode == Opcode.MOVE_RESULT_OBJECT && it.location.index < firstJumboIndex
            }
            val keyRegister = (keyLoad as OneRegisterInstruction).registerA

            // Rewriting a blocked unit's type token to an invalid one routes it to
            // the parser's own "unknown FeedItem type" branch, which yields an
            // invalid item Instagram filters out downstream — no throw, no crash.
            addInstructions(
                keyLoad.location.index + 1,
                "invoke-static/range { v$keyRegister .. v$keyRegister }, " +
                    "$BLOCK_CLASS->replaceFeedItemType(Ljava/lang/String;)Ljava/lang/String;\n" +
                    "move-result-object v$keyRegister",
            )

            // Every media a unit wraps ("media_or_ad" and the few other units
            // carrying one) goes through Block.filterFeedMedia, which returns null
            // for a sponsored post or a Reel. A null media leaves the unit as empty as an
            // unknown type does, so Instagram drops it the same way. Walked
            // backwards so the insertions do not shift the indices still to visit.
            instructions
                .filter { it.isMediaParse() }
                .map { instructions[it.location.index + 1] }
                .filter { it.opcode == Opcode.MOVE_RESULT_OBJECT }
                .reversed()
                .forEach { result ->
                    val register = (result as OneRegisterInstruction).registerA
                    addInstructions(
                        result.location.index + 1,
                        "invoke-static/range { v$register .. v$register }, " +
                            "$BLOCK_CLASS->filterFeedMedia(Ljava/lang/Object;)Ljava/lang/Object;\n" +
                            "move-result-object v$register\n" +
                            "check-cast v$register, $MEDIA_TYPE",
                    )
                }
        }

        // Hand every field name of every media parsed to Block.onMediaField, which
        // is how it learns that the media is an ad (see Block.AD_MEDIA_FIELDS).
        val legacyParser = MediaParseFromJsonFingerprint.methodOrNull
        val inlineParser = if (legacyParser == null) MediaParseInlineFingerprint.methodOrNull else null
        if (legacyParser != null) legacyParser.apply {
            val dispatch = instructions.first { it.isMediaFieldDispatch() }
            val keyRegister = (dispatch as FiveRegisterInstruction).registerD
            addInstructions(
                dispatch.location.index,
                "invoke-static/range { v$keyRegister .. v$keyRegister }, " +
                    "$BLOCK_CLASS->onMediaField(Ljava/lang/String;)V",
            )
        } else if (inlineParser != null) {
            inlineParser.hookMediaFieldHash()
        } else {
            val wrapper = MediaParseWrapperFingerprint.method
            val reference = (wrapper.instructions.first { it.isMediaCodecCall(wrapper) }
                as ReferenceInstruction).reference as MethodReference
            Fingerprint(
                definingClass = reference.definingClass,
                name = reference.name,
                parameters = reference.parameterTypes.map { it.toString() },
                returnType = reference.returnType,
            ).method.hookMediaFieldHash()
            // The same codec also has a direct model parser (including carousel
            // children); it does not go through the wrapper's map-building path.
            Fingerprint(
                definingClass = reference.definingClass,
                name = "unsafeParseFromJson",
            ).method.hookMediaFieldHash()
        }
    }
}
