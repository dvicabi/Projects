package com.feurstagram.patches.direct

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.feurstagram.patches.network.feedItemFilterPatch
import com.feurstagram.patches.quoicouview.addBridge
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION
import org.w3c.dom.Element

private const val LOADER = "Lcom/feurstagram/extension/SharedReelLoader;"
private const val CALLBACK = "Lcom/feurstagram/extension/NativeReelCallback;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val CONTEXT = "Landroid/content/Context;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val PUBLIC_STATIC = 9
private const val STORY_HANDLER = "LX/0Fvp;"
private const val INTERACTIVE = "Lcom/instagram/reels/interactive/Interactive;"
private const val PRODUCT = "Lcom/instagram/model/mediatype/ProductType;"
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val PLAYER_ACTIVITY = "com.feurstagram.extension.SingleReelActivity"

/** A real opaque screen makes the source Story receive Android's pause/stop lifecycle. */
private val singleReelActivityPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.documentElement.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("Manifest application is missing")
            val activities = application.getElementsByTagName("activity")
            val existing = (0 until activities.length).map { activities.item(it) as Element }
                .filter { it.getAttribute("android:name") == PLAYER_ACTIVITY }
            if (existing.size > 1) throw PatchException("Duplicate isolated player Activity")
            val activity = existing.singleOrNull() ?: document.createElement("activity").also {
                application.appendChild(it)
            }
            activity.setAttribute("android:name", PLAYER_ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:theme", "@android:style/Theme.Material.NoActionBar")
            activity.setAttribute("android:hardwareAccelerated", "true")
            // Use the caller's existing task/back stack, with ordinary Activity
            // lifecycle. No translucent, floating, noHistory or separate task.
        }
    }
}

/** Story interactive-card click, not the story pager or global Clips launcher. */
private object StorySharedReelFingerprint : Fingerprint(
    definingClass = STORY_HANDLER,
    name = "A01",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "LX/0LBa;", INTERACTIVE, "Z"),
)


/**
 * This is DirectClipsViewerNavigator.navigateToClipsViewer, not the global
 * Clips launcher. Its native branch writes the selected media ID and message
 * identity into the sparse Direct media viewer's arguments. Ordinary shared
 * attachment clicks, deferred XMA actions and Direct auto-open call this entry.
 */
private object DirectSelectedReelFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "sparse_media_viewer_media_id",
        "sparse_media_viewer_reply_message_id",
        "Must pass either mediaId or mediaCode",
    ),
    custom = { method, _ ->
        val p = method.parameterTypes.map { it.toString() }
        !AccessFlags.STATIC.isSet(method.accessFlags) && p.size == 23 &&
            p[0] == "Landroid/graphics/RectF;" && p[1] == "Landroid/view/View;" &&
            p[4] == "Lcom/instagram/model/direct/messageid/MessageIdentifier;" &&
            p[10] == STRING && p[11] == STRING && p.takeLast(4).all { it == "Z" }
    },
)

/** Native authenticated fetch of an explicit clips_media_ids JSON array. */
private object SelectedReelFetchFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/clips/api/ClipsApiUtilHelper;",
    strings = listOf("clips/items/", "clips_media_ids", "container_module"),
    custom = { method, _ ->
        val p = method.parameterTypes.map { it.toString() }
        AccessFlags.STATIC.isSet(method.accessFlags) && p.size == 4 &&
            p[0] == SESSION && p[1] != STRING && p[2] == STRING && p[3] == STRING
    },
)

/** A small existing caller provides the exact native request scheduler. */
private object SelectedReelScheduleFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("android_purge_26_q2_ClipsSuggestedCreatorsItemDefinition_getMediaFromServer"),
    custom = { method, _ ->
        val p = method.parameterTypes.map { it.toString() }
        p.size == 3 && p[1] == SESSION && p[2] == STRING
    },
)

private fun MethodReference.params() = parameterTypes.map { it.toString() }

/**
 * Supported only for the inspected 456.0.0.53.78 build. The request and callback
 * references are resolved from the real APK. Pinned model accessors below are
 * checked before patching, so a changed APK fails the build instead of silently
 * launching Instagram's pageable Reel viewer.
 */
@Suppress("unused")
val singleSharedReelPatch = bytecodePatch(
    name = "Single shared Reel player",
    description = "Intercepts selected Direct and Story-shared Reel attachments, fetches only that media " +
        "through the existing authenticated request stack, and opens an isolated video " +
        "player with no next item or native Reel navigation.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(feedItemFilterPatch, singleReelActivityPatch)
    extendWith(EXTENSION)

    execute {
        val direct = DirectSelectedReelFingerprint.method
        val navigator = DirectSelectedReelFingerprint.classDef
        val navigatorType = navigator.type
        val sessionField = navigator.fields.singleOrNull { it.type == SESSION }
            ?: throw PatchException("Direct Reel navigator has no unique session")
        val contextCallIndex = direct.instructions.indexOfFirst {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == FRAGMENT && ref.name == "getContext" && ref.params().isEmpty()
        }
        if (contextCallIndex < 1) throw PatchException("Direct Reel navigator context access changed")
        val fragmentField = (direct.instructions[contextCallIndex - 1] as? ReferenceInstruction)
            ?.reference as? FieldReference
            ?: throw PatchException("Direct Reel navigator has no fragment field")
        if (fragmentField.definingClass != navigatorType) {
            throw PatchException("Direct Reel navigator context owner changed")
        }
        if (!AccessFlags.PUBLIC.isSet(sessionField.accessFlags) ||
            navigator.fields.none { it.name == fragmentField.name && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
            throw PatchException("Direct Reel navigator fields are no longer public")
        }

        // Verify the selected-ID/shortcode parameter layout against its actual
        // native first instruction. In the supported build p11 is first copied
        // as media ID, and p12 is converted only if p11 is absent.
        val localCount = direct.implementation!!.registerCount - 24
        val firstMove = direct.instructions.firstOrNull()
        val firstRegisters = firstMove as? com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
        if (firstMove?.opcode != Opcode.MOVE_OBJECT_FROM16 || firstRegisters?.registerB != localCount + 11) {
            throw PatchException("Direct Reel selected-ID argument changed")
        }

        val fetch = SelectedReelFetchFingerprint.method
        val requestType = fetch.returnType
        val requestClass = classDefBy(requestType)
        val finish = requestClass.methods.singleOrNull { method ->
            val strings = method.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string
            } ?: emptyList()
            strings.containsAll(listOf("onFinish", "onFail", "onSuccess"))
        } ?: throw PatchException("Native Reel request callback dispatch changed")
        val finishRefs = finish.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }
        val success = finish.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_VIRTUAL }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.distinct().singleOrNull {
            it.returnType == "V" && it.params() == listOf(OBJECT)
        } ?: throw PatchException("Native Reel success callback changed")
        val nativeCallback = success.definingClass
        val callbackClass = classDefBy(nativeCallback)
        if (!AccessFlags.PUBLIC.isSet(callbackClass.accessFlags) || AccessFlags.FINAL.isSet(callbackClass.accessFlags) ||
            callbackClass.superclass != OBJECT || callbackClass.methods.any { it.name == "<init>" }) {
            throw PatchException("Native Reel callback construction contract changed")
        }
        val failure = finishRefs.distinct().singleOrNull {
            it.definingClass == nativeCallback && it.returnType == "V" &&
                it.params().size == 1 && it.params()[0] != OBJECT
        } ?: throw PatchException("Native Reel failure callback changed")
        val attach = requestClass.methods.singleOrNull {
            it.parameterTypes.map { p -> p.toString() } == listOf(nativeCallback) && it.returnType == "V"
        } ?: throw PatchException("Native Reel callback attachment changed")
        if (requestClass.methods.none { it.name == "cancel" && it.parameterTypes.isEmpty() && it.returnType == "V" }) {
            throw PatchException("Native Reel request cannot be cancelled")
        }
        val scheduleRefs = SelectedReelScheduleFingerprint.method.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }
        val scheduler = scheduleRefs.distinct().singleOrNull {
            it.returnType == "V" && it.params().size == 2 && it.params()[1] == "I"
        } ?: throw PatchException("Native Reel request scheduler changed")
        if (scheduleRefs.none { it.toString() == fetch.toString() }) {
            throw PatchException("Native Reel scheduler no longer schedules the resolved fetch")
        }

        // These exact response accessors were inspected in the supported APK:
        // Ctz maps raw ClipsItemDict.A02 Media objects; Ctx maps legacy items.
        val responseType = "LX/08Fv;"
        val responseClass = classDefBy(responseType)
        for (name in listOf("Ctz", "Ctx")) {
            val method = responseClass.methods.singleOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == LIST
            } ?: throw PatchException("Selected Reel response $name accessor changed")
            if (method.implementation?.instructions?.none {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type == MEDIA
                } != false) throw PatchException("Selected Reel response accessor no longer extracts Media")
        }
        if (classDefBy(MEDIA).methods.none { it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == STRING }) {
            throw PatchException("Native Media ID accessor changed")
        }

        // Preserve Instagram's own shortcode -> decimal media ID conversion.
        val codeType = "LX/0I8d;"
        val locatorType = "LX/01om;"
        if (direct.instructions.none {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == codeType && ref.name == "A00" && ref.params() == listOf(STRING) && ref.returnType == STRING
            } || classDefBy(locatorType).methods.none {
                it.name == "A01" && it.parameterTypes.map { p -> p.toString() } == listOf("I") && it.returnType == OBJECT
            }) throw PatchException("Native Reel shortcode conversion changed")

        mutableClassDefBy(LOADER).apply {
            fun bridge(name: String, params: List<String>, result: String, registers: Int, code: String) {
                val stub = methods.singleOrNull { it.name == name && it.parameterTypes.map { p -> p.toString() } == params }
                    ?: throw PatchException("Missing SharedReelLoader.$name bridge stub")
                methods.remove(stub)
                addBridge(name, params, result, registers, code, PUBLIC_STATIC)
            }

            addBridge("openFromDirect", listOf(OBJECT, STRING, STRING), "V", 7, """
                check-cast p0, $navigatorType
                iget-object v0, p0, $navigatorType->${fragmentField.name}:${fragmentField.type}
                invoke-virtual { v0 }, $FRAGMENT->getContext()$CONTEXT
                move-result-object v0
                iget-object v1, p0, $navigatorType->${sessionField.name}:$SESSION
                invoke-static { v0, v1, p1, p2 }, $LOADER->open($CONTEXT$OBJECT$STRING$STRING)V
                return-void
            """, PUBLIC_STATIC)

            bridge("nativeIdFromCode", listOf(STRING), STRING, 3, """
                const v0, 0x3c070
                invoke-static { v0 }, $locatorType->A01(I)$OBJECT
                move-result-object v0
                check-cast v0, $codeType
                invoke-virtual { v0, p0 }, $codeType->A00($STRING)$STRING
                move-result-object v0
                return-object v0
            """)

            bridge("nativeFetch", listOf(OBJECT, STRING, STRING, CALLBACK), OBJECT, 6, """
                check-cast p0, $SESSION
                const/4 v0, 0x0
                invoke-static { p0, v0, p1, p2 }, ${fetch.definingClass}->${fetch.name}($SESSION${fetch.parameterTypes[1]}$STRING$STRING)$requestType
                move-result-object v0
                invoke-virtual { v0, p3 }, $requestType->${attach.name}($nativeCallback)V
                const v1, 0x518fd50f
                invoke-static { v0, v1 }, ${scheduler.definingClass}->${scheduler.name}(${scheduler.params()[0]}I)V
                return-object v0
            """)

            bridge("nativeCancel", listOf(OBJECT), "V", 2, """
                instance-of v0, p0, $requestType
                if-eqz v0, :done
                check-cast p0, $requestType
                invoke-virtual { p0 }, $requestType->cancel()V
                :done
                return-void
            """)

            bridge("nativeResponseMedia", listOf(OBJECT), LIST, 3, """
                instance-of v0, p0, $responseType
                if-eqz v0, :missing
                check-cast p0, $responseType
                invoke-virtual { p0 }, $responseType->Ctz()$LIST
                move-result-object v0
                if-eqz v0, :legacy
                invoke-interface { v0 }, $LIST->isEmpty()Z
                move-result v1
                if-eqz v1, :result
                :legacy
                invoke-virtual { p0 }, $responseType->Ctx()$LIST
                move-result-object v0
                :result
                return-object v0
                :missing
                const/4 v0, 0x0
                return-object v0
            """)

            bridge("nativeMediaId", listOf(OBJECT), STRING, 2, """
                instance-of v0, p0, $MEDIA
                if-eqz v0, :missing
                check-cast p0, $MEDIA
                invoke-virtual { p0 }, $MEDIA->getId()$STRING
                move-result-object v0
                return-object v0
                :missing
                const/4 v0, 0x0
                return-object v0
            """)
        }

        mutableClassDefBy(CALLBACK).apply {
            setSuperClass(nativeCallback)
            // The original native callback subclasses also invoke Object's
            // constructor directly: this no-constructor callback base is a
            // Redex output class. Preserve the compiled Java constructor.
            addBridge(success.name, listOf(OBJECT), "V", 2, """
                invoke-virtual { p0, p1 }, $CALLBACK->success($OBJECT)V
                return-void
            """)
            addBridge(failure.name, failure.params(), "V", 2, """
                invoke-virtual { p0, p1 }, $CALLBACK->failure($OBJECT)V
                return-void
            """)
        }

        // Organic Reel reshare sticker in Stories. The pinned native route uses
        // ProductType.CLIPS (A07), then the sticker's A1k media identifier with
        // A1l as its null fallback, before invoking the native Clips launcher.
        // Do not intercept IGTV, regular feed posts, normal stories, or arbitrary
        // deep links. Inspect the exact branch and ID reads before injecting.
        val story = StorySharedReelFingerprint.method
        val storyClass = StorySharedReelFingerprint.classDef
        val storySession = storyClass.fields.singleOrNull { it.type == SESSION && it.name == "A00" }
            ?: throw PatchException("Story sticker session field changed")
        val interactiveClass = classDefBy(INTERACTIVE)
        for (field in listOf("A1k", "A1l")) {
            if (interactiveClass.fields.none { it.name == field && it.type == STRING && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
                throw PatchException("Story sticker media identifier $field changed")
            }
        }
        val productInit = classDefBy(PRODUCT).methods.singleOrNull { it.name == "<clinit>" }
            ?: throw PatchException("ProductType enum initializer missing")
        val productCode = productInit.implementation?.instructions?.toList()
            ?: throw PatchException("ProductType enum initializer missing code")
        val enumIndex = productCode.indexOfFirst {
            val f = (it as? ReferenceInstruction)?.reference as? FieldReference
            it.opcode == Opcode.SPUT_OBJECT && f?.definingClass == PRODUCT && f.name == "A07"
        }
        if (enumIndex < 0 || productCode.subList(maxOf(0, enumIndex - 12), enumIndex).none {
                ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == "CLIPS"
            }) throw PatchException("Story product discriminator is not CLIPS")

        val storyCode = story.instructions.toList()
        fun storyField(index: Int): FieldReference? =
            (storyCode.getOrNull(index) as? ReferenceInstruction)?.reference as? FieldReference
        val branches = storyCode.indices.filter { i ->
            val f = storyField(i)
            val next = storyField(i + 2)
            storyCode[i].opcode == Opcode.SGET_OBJECT && f?.definingClass == PRODUCT && f.name == "A07" &&
                storyCode.getOrNull(i - 1)?.opcode == Opcode.RETURN_VOID &&
                storyCode.getOrNull(i + 1)?.opcode == Opcode.IF_EQ &&
                next?.definingClass == PRODUCT && next.name == "A0A"
        }
        val branch = branches.singleOrNull()
            ?: throw PatchException("Organic Story Reel sticker branch changed")
        val enumRegister = (storyCode[branch] as OneRegisterInstruction).registerA
        val comparison = storyCode[branch + 1] as TwoRegisterInstruction
        if (comparison.registerB != enumRegister || comparison.registerA == enumRegister) {
            throw PatchException("Story Reel product comparison changed")
        }
        // Native click-handler obtains the current activity before reaching this branch.
        val activityIndex = storyCode.indices.firstOrNull { i ->
            val ref = (storyCode[i] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == FRAGMENT && ref.name == "getActivity" &&
                ref.params().isEmpty() && ref.returnType == FRAGMENT_ACTIVITY &&
                storyCode.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
        } ?: throw PatchException("Story sticker activity lookup changed")
        val activityRegister = (storyCode[activityIndex + 1] as OneRegisterInstruction).registerA
        // The target was inspected at register 15; fail the build on layout drift
        // rather than passing another object or overwriting the owner register.
        if (activityRegister != 15 || story.implementation!!.registerCount != 42 || activityIndex >= branch) {
            throw PatchException("Story sticker activity register layout changed")
        }
        // Validate the existing identifier precedence and launcher in the native
        // branch. These are identifiers of the shared post, not the story ID.
        val selected = storyCode.indices.filter { i ->
            val primary = storyField(i)
            val fallback = storyField(i + 2)
            primary?.definingClass == INTERACTIVE && primary.name == "A1k" &&
                storyCode[i].opcode == Opcode.IGET_OBJECT && storyCode.getOrNull(i + 1)?.opcode == Opcode.IF_NEZ &&
                fallback?.definingClass == INTERACTIVE && fallback.name == "A1l" &&
                storyCode.getOrNull(i + 2)?.opcode == Opcode.IGET_OBJECT
        }.singleOrNull { it > branch }
            ?: throw PatchException("Story Reel selected-ID precedence changed")
        if (storyCode.drop(selected).take(100).none {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "LX/0Lxx;" && ref.name == "A00" &&
                    ref.params().size == 10 && ref.returnType == "V"
            }) throw PatchException("Story Reel native launcher changed")
        // Non-CLIPS falls through untouched. CLIPS is always consumed, including
        // unavailable media, so the original recommendation pager never opens.
        story.addInstructions(branch + 1, """
            if-ne v${comparison.registerA}, v${comparison.registerB}, :cabessa_story_native
            move-object/from16 v0, p0
            iget-object v1, v0, $STORY_HANDLER->${storySession.name}:$SESSION
            move-object/from16 v2, p3
            iget-object v3, v2, $INTERACTIVE->A1k:$STRING
            iget-object v2, v2, $INTERACTIVE->A1l:$STRING
            invoke-static { v$activityRegister, v1, v3, v2 }, $LOADER->openFromStory($CONTEXT$OBJECT$STRING$STRING)V
            return-void
            :cabessa_story_native
            nop
        """.trimIndent())

        // No native launcher or pager is constructed, even for a missing,
        // deleted, private or unavailable item. The loader owns those errors.
        direct.addInstructions(0, """
            move-object/from16 v0, p0
            move-object/from16 v1, p11
            move-object/from16 v2, p12
            invoke-static { v0, v1, v2 }, $LOADER->openFromDirect($OBJECT$STRING$STRING)V
            return-void
        """.trimIndent())
    }
}
