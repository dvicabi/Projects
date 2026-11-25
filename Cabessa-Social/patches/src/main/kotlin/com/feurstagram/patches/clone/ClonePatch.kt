package com.feurstagram.patches.clone

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.asSequence
import app.morphe.util.findElementByAttributeValue
import app.morphe.util.findMutableMethodOf
import app.morphe.util.forEachChildElement
import com.feurstagram.patches.shared.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE_NAME = "com.instagram.android"
private const val SOCIAL_PACKAGE_NAME = "com.dvircab.social"
private const val SOCIAL_APP_NAME = "Cabessa Social"

@Suppress("unused")
val clonePatch = resourcePatch(
    name = "Clone",
    description = "Installs Cabessa Social as its own package and removes release-unsafe manifest settings.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    var bytecodePatchContext: BytecodePatchContext? = null

    dependsOn(
        // Expose the bytecode context so we can rewrite string references from
        // within this resource patch.
        bytecodePatch {
            execute {
                bytecodePatchContext = this
            }
        },
    )

    execute {
        val newPackageName = SOCIAL_PACKAGE_NAME

        // Pairs of (original authority, renamed authority).
        val providerReplacements = mutableListOf<Pair<String, String>>()

        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val originalLabel = (manifest.getElementsByTagName("application").item(0) as Element)
                .getAttribute("android:label")

            manifest.setAttribute("package", newPackageName)

            // Relative component names still refer to classes in the original
            // APK; changing the package must not redirect them into our namespace.
            for (tag in listOf("application", "activity", "activity-alias", "service", "receiver", "provider")) {
                manifest.getElementsByTagName(tag).asSequence().map { it as Element }.forEach { component ->
                    for (attribute in listOf("android:name", "android:targetActivity", "android:parentActivityName")) {
                        val value = component.getAttribute(attribute)
                        if (value.startsWith('.')) {
                            component.setAttribute(attribute, ORIGINAL_PACKAGE_NAME + value)
                        } else if (value.isNotEmpty() && !value.contains('.')) {
                            component.setAttribute(attribute, "$ORIGINAL_PACKAGE_NAME.$value")
                        }
                    }
                    // Launcher aliases can override the application label. Keep
                    // every icon variant identifiable beside official Instagram.
                    val launcher = component.getElementsByTagName("category").asSequence()
                        .map { it as Element }
                        .any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
                    if (launcher || (originalLabel.isNotEmpty() &&
                                component.getAttribute("android:label") == originalLabel)) {
                        component.setAttribute("android:label", SOCIAL_APP_NAME)
                    }
                }
            }

            manifest.getElementsByTagName("application").asSequence().map { it as Element }.forEach {
                it.setAttribute("android:label", SOCIAL_APP_NAME)
                it.setAttribute("android:debuggable", "false")
                it.setAttribute("android:testOnly", "false")
            }
            for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
                manifest.getElementsByTagName(tag).asSequence().map { it as Element }
                    .filter { it.getAttribute("android:name") == "android.permission.REQUEST_INSTALL_PACKAGES" }
                    .toList().forEach { it.parentNode.removeChild(it) }
            }

            val permissions = manifest.getElementsByTagName("permission")
            val usesPermissions = manifest.getElementsByTagName("uses-permission")

            permissions.asSequence().map { it as Element }.forEach {
                val oldName = it.getAttribute("android:name")
                if (oldName.startsWith('.')) return@forEach
                val newName = oldName.replace(ORIGINAL_PACKAGE_NAME, newPackageName)
                it.setAttribute("android:name", newName)

                usesPermissions
                    .findElementByAttributeValue("android:name", oldName)
                    ?.setAttribute("android:name", newName)
            }

            val providers = manifest.getElementsByTagName("provider").asSequence().map { it as Element }
            for (provider in providers) {
                val oldAuthority = provider.getAttribute("android:authorities")
                val newAuthority =
                    if (oldAuthority.startsWith("$ORIGINAL_PACKAGE_NAME.")) {
                        oldAuthority.replaceFirst(ORIGINAL_PACKAGE_NAME, newPackageName)
                    } else {
                        "${newPackageName}_$oldAuthority"
                    }

                provider.setAttribute("android:authorities", newAuthority)
                providerReplacements.add(oldAuthority to newAuthority)
            }
        }

        // Preserve the original resources while giving launchers and app-owned
        // labels one fixed identity. There is no runtime package/name setting.
        document("res/values/strings.xml").use { document ->
            document.documentElement.forEachChildElement {
                if (it.textContent == "Instagram") {
                    it.textContent = SOCIAL_APP_NAME
                }
            }
        }

        // Rewrite the package name (and provider authorities) wherever they are
        // referenced as string constants in the bytecode, otherwise some
        // surfaces (e.g. video stories playing audio only) break in the clone.
        context(bytecodePatchContext!!) {
            transformStringReferences { string ->
                if (string == ORIGINAL_PACKAGE_NAME) {
                    return@transformStringReferences newPackageName
                }
                val matched = providerReplacements.find { string.contains(it.first) }
                if (matched != null) {
                    string.replaceFirst(matched.first, matched.second)
                } else {
                    null
                }
            }
        }
        bytecodePatchContext = null
    }
}

context(patchContext: BytecodePatchContext)
private fun transformStringReferences(transform: (str: String) -> String?) {
    patchContext.getAllClassesWithStrings().forEach { classDef ->
        val mutableClass by lazy { patchContext.mutableClassDefBy(classDef) }

        classDef.methods.forEach { method ->
            val mutableMethod by lazy { mutableClass.findMutableMethodOf(method) }

            method.implementation?.instructions?.forEachIndexed { index, instruction ->
                val string = instruction.getReference<StringReference>()?.string
                    ?: return@forEachIndexed
                val transformed = transform(string) ?: return@forEachIndexed

                mutableMethod.replaceInstruction(
                    index,
                    BuilderInstruction21c(
                        Opcode.CONST_STRING,
                        (instruction as OneRegisterInstruction).registerA,
                        ImmutableStringReference(transformed),
                    ),
                )
            }
        }
    }
}
