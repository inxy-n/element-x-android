import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.libraries.htmlrenderer.api"
}

dependencies {
    api(projects.libraries.matrix.api)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.uiCommon)
    implementation(projects.libraries.uiStrings)

    api(libs.jsoup)
    implementation("io.github.huarangmeng:latex-renderer:1.7.6")
    implementation("io.github.huarangmeng:latex-fonts-katex:1.7.6")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    testCommonDependencies(libs)
}
