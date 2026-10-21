"""
Tier 1 Feature Coverage: Release Build & Packaging Guardrails (Requirement R4)
Tests Gradle toolchain, assembleRelease execution, APK generation, and Proguard rules.
"""

import unittest
import os
import zipfile
from tests.e2e.fixtures.helpers import (
    PROJECT_ROOT,
    run_gradle_command
)

class TestFeatureAssembleRelease(unittest.TestCase):

    def setUp(self):
        self.build_gradle_path = os.path.join(PROJECT_ROOT, "app", "build.gradle.kts")
        self.proguard_rules_path = os.path.join(PROJECT_ROOT, "app", "proguard-rules.pro")
        self.gradle_props_path = os.path.join(PROJECT_ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
        self.release_apk_path = os.path.join(PROJECT_ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")

    def test_01_toolchain_and_sdk_configuration(self):
        """Verify build.gradle.kts defines required SDK targets and JVM 17 compatibility."""
        with open(self.build_gradle_path, "r", encoding="utf-8") as f:
            content = f.read()

        self.assertIn("compileSdk = 34", content, "compileSdk must be 34")
        self.assertIn("minSdk = 24", content, "minSdk must be 24")
        self.assertIn("targetSdk = 34", content, "targetSdk must be 34")
        self.assertTrue(
            "JavaVersion.VERSION_17" in content or "JavaVersion.VERSION_21" in content,
            "JVM target must be at least Java 17"
        )

    def test_02_proguard_rules_preserve_models_and_interfaces(self):
        """Verify proguard-rules.pro retains data models and annotations."""
        self.assertTrue(os.path.exists(self.proguard_rules_path), "proguard-rules.pro must exist")
        with open(self.proguard_rules_path, "r", encoding="utf-8") as f:
            rules = f.read()

        self.assertIn("com.dlive.ptstream.data", rules, "Must preserve data package classes")
        self.assertIn("SerializedName", rules, "Must preserve @SerializedName annotations")

    def test_03_release_signing_configuration_present(self):
        """Verify release build block specifies a valid signingConfig."""
        with open(self.build_gradle_path, "r", encoding="utf-8") as f:
            content = f.read()

        self.assertIn("signingConfig =", content, "buildTypes.release must specify signingConfig")

    def test_04_gradle_wrapper_files_intact(self):
        """Verify Gradle wrapper scripts and properties are intact with Gradle 8.6."""
        gradlew_bat = os.path.join(PROJECT_ROOT, "gradlew.bat")
        self.assertTrue(os.path.exists(gradlew_bat), "gradlew.bat must exist")
        self.assertTrue(os.path.exists(self.gradle_props_path), "gradle-wrapper.properties must exist")

        with open(self.gradle_props_path, "r", encoding="utf-8") as f:
            props = f.read()
        self.assertIn("gradle-8.6", props, "Gradle distribution must be 8.6")

    def test_05_assemble_release_build_execution(self):
        """Verify ./gradlew assembleRelease executes successfully and produces release APK."""
        code, stdout, stderr = run_gradle_command("assembleRelease", timeout=90.0)
        self.assertEqual(code, 0, f"assembleRelease failed with code {code}:\n{stderr}\n{stdout[-1000:]}")
        self.assertTrue(os.path.exists(self.release_apk_path), f"Release APK not found at {self.release_apk_path}")
        
        apk_size = os.path.getsize(self.release_apk_path)
        self.assertGreater(apk_size, 5 * 1024 * 1024, f"Release APK is unexpectedly small: {apk_size} bytes")

    def test_06_release_apk_contains_master_catalog(self):
        """Verify generated release APK contains channels.json assets and classes.dex."""
        self.assertTrue(os.path.exists(self.release_apk_path))
        with zipfile.ZipFile(self.release_apk_path, "r") as apk:
            namelist = apk.namelist()
            self.assertIn("assets/channels.json", namelist, "Release APK must package assets/channels.json")
            dex_files = [n for n in namelist if n.endswith(".dex")]
            self.assertGreaterEqual(len(dex_files), 1, "Release APK must contain at least one classes.dex file")
            self.assertIn("AndroidManifest.xml", namelist, "Release APK must contain AndroidManifest.xml")

if __name__ == "__main__":
    unittest.main()
