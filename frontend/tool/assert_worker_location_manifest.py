#!/usr/bin/env python3
"""Fail the build if the worker APK cannot legally run its location service.

WHY THIS IS A BUILD STEP AND NOT A CODE REVIEW NOTE. The worker app shares
a rider's position with the shop while a delivery is out, and on Android that
requires four things to agree: the two location permissions, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_LOCATION, and a <service> whose foregroundServiceType is
"location". Get any one of them wrong and nothing fails at build time - the app
installs, opens, scans, and then throws SecurityException the first time a
rider actually starts a delivery, on Android 14 only, in release only. That is
the most expensive possible place to find out.

The mirror image matters just as much: ACCESS_BACKGROUND_LOCATION must never
appear. A foreground service follows a rider while they are working; background
location would follow them when they are not, and the only thing standing
between those two is this file and a plugin's merged manifest.

verify_apk_release.py re-checks the permissions in the BUILT APK, after
manifest merging. This checks the source, so the failure names the line to fix.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
TOOLS = "http://schemas.android.com/tools"

WORKER_MANIFEST = "android/app/src/workerStandalone/AndroidManifest.xml"
CUSTOMER_OVERLAY = "android/app/src/main/AndroidManifest.xml"
ADMIN_OVERLAY = "android/app/src/admin/AndroidManifest.xml"

REQUIRED = (
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
)

# Declared with tools:node="remove" rather than merely absent: absent means
# "no plugin has added it yet".
MUST_BE_REMOVED = ("android.permission.ACCESS_BACKGROUND_LOCATION",)

LOCATION_SERVICE = "com.baseflow.geolocator.GeolocatorLocationService"


def _name(element: ET.Element) -> str:
    return element.get(f"{{{ANDROID}}}name", "")


def _node(element: ET.Element) -> str:
    return element.get(f"{{{TOOLS}}}node", "")


def granted_and_removed(root: ET.Element) -> tuple[set[str], set[str]]:
    granted: set[str] = set()
    removed: set[str] = set()
    for permission in root.findall("uses-permission"):
        if _node(permission) == "remove":
            removed.add(_name(permission))
        else:
            granted.add(_name(permission))
    return granted, removed


def worker_problems(root: ET.Element) -> list[str]:
    problems: list[str] = []
    granted, removed = granted_and_removed(root)

    for permission in REQUIRED:
        if permission not in granted:
            problems.append(f"worker manifest must declare {permission}")

    for permission in MUST_BE_REMOVED:
        if permission in granted:
            problems.append(f"worker manifest must NOT grant {permission}")
        elif permission not in removed:
            problems.append(
                f'worker manifest must strip {permission} with tools:node="remove" '
                "so a merged plugin manifest cannot reintroduce it"
            )

    application = root.find("application")
    services = application.findall("service") if application is not None else []
    location = [s for s in services if _name(s) == LOCATION_SERVICE]
    if not location:
        problems.append(
            f"worker manifest must declare <service> {LOCATION_SERVICE} so its "
            "foregroundServiceType cannot be lost in a dependency bump"
        )
    for service in location:
        kind = service.get(f"{{{ANDROID}}}foregroundServiceType", "")
        if kind != "location":
            problems.append(
                f'{LOCATION_SERVICE} must set android:foregroundServiceType="location", '
                f"found {kind!r}"
            )
    return problems


def other_app_problems(label: str, root: ET.Element) -> list[str]:
    """No other APK may carry the foreground-service pair."""
    granted, _removed = granted_and_removed(root)
    return [
        f"{label} manifest must not declare {permission} - only the worker app "
        "runs a foreground service, and declaring it drags the whole app into "
        "Play's Foreground Service review"
        for permission in ("android.permission.FOREGROUND_SERVICE",
                           "android.permission.FOREGROUND_SERVICE_LOCATION")
        if permission in granted
    ]


def parse(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def self_test() -> None:
    def root_of(xml: str) -> ET.Element:
        return ET.fromstring(xml)

    complete = """
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:tools="http://schemas.android.com/tools">
      <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
      <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
      <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
      <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" tools:node="remove"/>
      <application>
        <service android:name="com.baseflow.geolocator.GeolocatorLocationService"
                 android:foregroundServiceType="location"/>
      </application>
    </manifest>"""
    assert not worker_problems(root_of(complete)), worker_problems(root_of(complete))

    # Each required permission, dropped one at a time.
    for permission in REQUIRED:
        broken = complete.replace(f'<uses-permission android:name="{permission}"/>', "")
        assert worker_problems(root_of(broken)), f"missing {permission} must fail"

    # Background location merely absent is NOT enough.
    absent = complete.replace(
        '<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" tools:node="remove"/>',
        "",
    )
    assert worker_problems(root_of(absent)), "absent background location must still fail"

    granted_background = complete.replace(
        '<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" tools:node="remove"/>',
        '<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>',
    )
    assert worker_problems(root_of(granted_background)), "granted background location must fail"

    no_type = complete.replace(' android:foregroundServiceType="location"', "")
    assert worker_problems(root_of(no_type)), "service without foregroundServiceType must fail"

    wrong_type = complete.replace('foregroundServiceType="location"',
                                  'foregroundServiceType="dataSync"')
    assert worker_problems(root_of(wrong_type)), "wrong foregroundServiceType must fail"

    no_service = complete.replace(
        '<service android:name="com.baseflow.geolocator.GeolocatorLocationService"\n'
        '                 android:foregroundServiceType="location"/>',
        "",
    )
    assert worker_problems(root_of(no_service)), "missing service must fail"

    clean_other = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>'
    assert not other_app_problems("customer", root_of(clean_other))
    leaky_other = """
    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
    </manifest>"""
    assert other_app_problems("customer", root_of(leaky_other)), "leaked FGS must fail"

    print("assert_worker_location_manifest.py self-test ok")


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
        return 0

    here = Path(__file__).resolve().parent.parent
    problems = worker_problems(parse(here / WORKER_MANIFEST))
    problems += other_app_problems("customer", parse(here / CUSTOMER_OVERLAY))
    problems += other_app_problems("admin", parse(here / ADMIN_OVERLAY))
    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 1
    print("worker location manifest ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
