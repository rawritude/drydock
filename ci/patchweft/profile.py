#!/usr/bin/env python3
"""Write the Drydock validation profile for one Patchweft host.

Profiles bind exact tool bytes and absolute paths, so each host gets its own
generated file. Jobs run in private attempt directories with a cleared
environment; the profile environment below is exactly what Gradle and the
Android plugin need.

  python3 ci/patchweft/profile.py \
      --java "$JAVA_HOME/bin/java" \
      --android-home "$ANDROID_HOME" \
      --gradle-home "$HOME/.gradle" > drydock-profile.json

Then register it (and see ci/patchweft/README.md for the full loop):

  patchweft --state "$STATE" register drydock-profile.json
"""
import argparse
import json
import os
import shutil
import sys


def refuse(message):
    sys.exit(f"profile.py: {message}")


def executable(name, argument):
    if not name:
        refuse(f"{argument} is required")
    path = os.path.abspath(os.path.expanduser(name))
    if not os.path.isfile(path) or not os.access(path, os.X_OK):
        refuse(f"{argument}: {path} is not an executable file")
    return path


def directory(name, argument, create=False):
    if not name:
        refuse(f"{argument} is required")
    path = os.path.abspath(os.path.expanduser(name))
    if create:
        os.makedirs(path, exist_ok=True)
    if not os.path.isdir(path):
        refuse(f"{argument}: {path} is not a directory")
    return path


default_java = ""
if os.environ.get("JAVA_HOME"):
    default_java = os.path.join(os.environ["JAVA_HOME"], "bin", "java")
elif shutil.which("java"):
    default_java = shutil.which("java")

parser = argparse.ArgumentParser(
    description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--java", default=default_java,
                    help="JDK 17 java binary (default: JAVA_HOME/bin/java, then PATH)")
parser.add_argument("--bash", default=shutil.which("bash") or "/usr/bin/bash",
                    help="bash that runs gradlew")
parser.add_argument("--android-home",
                    default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "",
                    help="Android SDK root (default: ANDROID_HOME, then ANDROID_SDK_ROOT)")
parser.add_argument("--gradle-home", default=os.path.join(os.path.expanduser("~"), ".gradle"),
                    help="persistent Gradle user home shared by attempts (created if missing)")
parser.add_argument("--name", default="drydock-ci")
args = parser.parse_args()

java = executable(args.java, "--java")
bash = executable(args.bash, "--bash")
android_home = directory(args.android_home, "--android-home")
gradle_home = directory(args.gradle_home, "--gradle-home", create=True)

for component in ("platforms/android-35", "build-tools/35.0.0"):
    if not os.path.isdir(os.path.join(android_home, component)):
        refuse(f"--android-home: missing {component}; install with: "
               'sdkmanager --install "platforms;android-35" "build-tools;35.0.0"')

AUDIT = ("The jobs read the Gradle project, sources under app/ and the CI definitions only. "
         "A revision that changes nothing else cannot change their outcome.")

profile = {
    "version": 1,
    "name": args.name,
    # Exploratory evidence: Drydock lands through GitHub, not through a Patchweft
    # frontier. Flip to true only when a certifying flow actually consumes it.
    "certifying": False,
    "tools": {
        "bash": bash,
        "java": java,
    },
    "environment": {
        "JAVA_HOME": os.path.dirname(os.path.dirname(java)),
        "ANDROID_HOME": android_home,
        "ANDROID_SDK_ROOT": android_home,
        "GRADLE_USER_HOME": gradle_home,
    },
    "inputs_file": "ci/patchweft/drydock-inputs.json",
    "jobs": [
        {
            "name": "test-lint",
            "command": ["bash", "./gradlew", "testDebugUnitTest", "lintDebug", "--no-daemon"],
            "slots": 8,
            "memory_mb": 8192,
            "timeout_seconds": 1800,
            "report": {"kind": "command"},
            "reuse": "declared",
            "audit": AUDIT,
        },
        {
            "name": "assemble",
            "command": ["bash", "./gradlew", "assembleDebug", "assembleRelease", "--no-daemon"],
            "slots": 8,
            "memory_mb": 8192,
            "timeout_seconds": 1800,
            "report": {"kind": "command"},
            "reuse": "declared",
            "audit": AUDIT,
        },
    ],
}

json.dump(profile, sys.stdout, indent=2)
sys.stdout.write("\n")
