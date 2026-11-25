group = "com.feurstagram"

patches {
    about {
        name = "Cabessa Social Patches"
        description = "Fixed social policy: stories, messages and a chronological Following feed; no Reels, Explore, Notes, updater or runtime content switches."
        source = "https://github.com/jean-voila/FeurStagram"
        author = "Dvir Cabessa; based on FeurStagram by Jean Herail"
        contact = "na"
        website = "na"
        license = "GPLv3"
    }
}

dependencies {
    // Provides app.morphe.util.* helpers (DOM, bytecode, references) used by the patches.
    implementation(libs.morphe.patches.library)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
