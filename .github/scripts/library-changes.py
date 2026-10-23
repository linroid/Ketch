#!/usr/bin/env python3
"""Decide whether a release publishes the libraries to Maven Central.

Maven Central limits how much an organization publishes each month, so a release that changes only
the apps leaves the libraries at their last published version. That version comes from Maven
Central's metadata, and its tag is compared with the release. Whatever cannot be checked
publishes. Prints the decision; on GitHub Actions also writes `publish=true|false` to the step's
outputs and the reason to the job summary.
"""

import argparse
import os
import re
import subprocess
import urllib.request
import xml.etree.ElementTree as ElementTree

METADATA_URL = "https://repo1.maven.org/maven2/com/linroid/ketch/api/maven-metadata.xml"

# Files outside the published modules that shape what they publish: dependency and plugin
# versions, the compiler options and opt-ins every module gets, the module list, and the group
# and POM.
SHARED_BUILD_FILES = (
  "build.gradle.kts",
  "settings.gradle.kts",
  "gradle.properties",
  "gradle/libs.versions.toml",
)

# Settings in gradle.properties that only tune how Gradle and the Kotlin daemon run the build.
BUILD_TUNING = ("org.gradle.", "kotlin.daemon.")

# A whole-line comment: `//`, `/*`, a KDoc line (`* `, not a spread `*args`) or `#`.
COMMENT = re.compile(r"(//|/\*|\*(\s|/|$)|#)")

# A module publishes when its build script applies the vanniktech plugin.
PUBLISH_PLUGIN = r"libs\.plugins\.mavenPublish|com\.vanniktech\.maven\.publish"

# Test source sets (commonTest, jvmTest, androidHostTest, test, ...) and notes are not published.
UNPUBLISHED = re.compile(r"/src/(test|[^/]*Test)/|\.md$")

MAX_LISTED_FILES = 50


def git(*args):
  return subprocess.run(
    ["git", *args], check=True, capture_output=True, text=True,
  ).stdout


def has_commit(ref):
  return subprocess.run(
    ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], capture_output=True,
  ).returncode == 0


def last_published_version(url):
  with urllib.request.urlopen(url, timeout=30) as response:
    versioning = ElementTree.fromstring(response.read()).find("versioning")
  version = None if versioning is None else (
    versioning.findtext("release") or versioning.findtext("latest")
  )
  if not version or not version.strip():
    raise ValueError("the metadata names no release")
  return version.strip()


def fetch_tag(tag):
  """Fetches [tag] from origin when the checkout lacks it, as a shallow one does."""
  ref = f"refs/tags/{tag}"
  if has_commit(ref):
    return True
  depth = ["--depth=1"] if git("rev-parse", "--is-shallow-repository").strip() == "true" else []
  subprocess.run(
    ["git", "fetch", "--quiet", "--no-tags", *depth, "origin", f"+{ref}:{ref}"],
    capture_output=True,
  )
  return has_commit(ref)


def published_modules(revision):
  result = subprocess.run(
    ["git", "grep", "-l", "-E", PUBLISH_PLUGIN, revision, "--", "*build.gradle.kts"],
    capture_output=True, text=True,
  )
  if result.returncode not in (0, 1):
    raise RuntimeError(result.stderr.strip())
  scripts = (line.removeprefix(f"{revision}:") for line in result.stdout.splitlines())
  return {os.path.dirname(script) for script in scripts} - {""}


def file_at(revision, path):
  result = subprocess.run(["git", "show", f"{revision}:{path}"], capture_output=True, text=True)
  return result.stdout if result.returncode == 0 else ""


def build_settings(path, text):
  """The lines of a shared build file that can change the output: not comments or blank lines,
  nor the build tuning of gradle.properties, whose order does not matter either."""
  lines = [line.strip() for line in text.splitlines()]
  lines = [line for line in lines if line and not COMMENT.match(line)]
  if path == "gradle.properties":
    lines = sorted(line for line in lines if not line.startswith(BUILD_TUNING))
  return lines


def changes_published_output(path, modules, base, head):
  if path in SHARED_BUILD_FILES:
    return build_settings(path, file_at(base, path)) != build_settings(path, file_at(head, path))
  if UNPUBLISHED.search(path):
    return False
  return any(path.startswith(f"{module}/") for module in modules)


def library_changes(base, head):
  """The files between [base] and [head] that change what the libraries publish."""
  # Modules published by either side, so a module that starts or stops publishing counts too.
  modules = published_modules(base) | published_modules(head)
  # Without renames, a file moved out of a module counts by its old path.
  changed = git("diff", "--name-only", "--no-renames", base, head).splitlines()
  return [path for path in changed if changes_published_output(path, modules, base, head)]


def decide(tag, head, force, published, metadata_url):
  """Returns whether to publish, why, and the library files that changed."""
  version = tag.removeprefix("v")
  if force:
    return True, f"Publishing {version}: forced by the `force-maven-central` input.", []
  try:
    published = published or last_published_version(metadata_url)
  except Exception as error:
    return True, (
      f"Publishing {version}: the last published version could not be read from Maven Central"
      f" ({error}), so the libraries publish anyway."
    ), []
  if published == version:
    return False, f"Skipped: {version} is already on Maven Central.", []
  base = f"v{published}"
  try:
    if not fetch_tag(base):
      return True, (
        f"Publishing {version}: tag `{base}` of the last published version is missing, so the"
        " libraries publish anyway."
      ), []
    changed = library_changes(f"refs/tags/{base}", head)
  except Exception as error:
    return True, (
      f"Publishing {version}: comparing it with `{base}` failed ({error}), so the libraries"
      " publish anyway."
    ), []
  if not changed:
    return False, (
      f"Skipped: no published library changed since {published} (`{base}`), the last version on"
      f" Maven Central, which stays the libraries' latest version. To publish {version} anyway,"
      f" run `gh workflow run release.yml --ref {tag} -f force-maven-central=true`."
    ), []
  files = "file" if len(changed) == 1 else "files"
  return True, (
    f"Publishing {version}: {len(changed)} library {files} changed since {published} (`{base}`),"
    " the last version on Maven Central."
  ), changed


def summary(reason, changed):
  lines = ["### Maven Central", "", reason, ""]
  if changed:
    lines += ["<details>", "<summary>Changed library files</summary>", ""]
    lines += [f"- `{path}`" for path in changed[:MAX_LISTED_FILES]]
    if len(changed) > MAX_LISTED_FILES:
      lines.append(f"- and {len(changed) - MAX_LISTED_FILES} more")
    lines += ["", "</details>", ""]
  return "\n".join(lines) + "\n"


def append(variable, text):
  path = os.environ.get(variable)
  if path:
    with open(path, "a", encoding="utf-8") as file:
      file.write(text)


if __name__ == "__main__":
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--tag", required=True, help="the release tag, such as v1.2.3")
  parser.add_argument("--head", default="HEAD", help="the revision that publishes")
  parser.add_argument("--force", action="store_true", help="publish even if no library changed")
  parser.add_argument(
    "--published", help="the last published version, instead of reading Maven Central's",
  )
  parser.add_argument("--metadata-url", default=METADATA_URL)
  args = parser.parse_args()
  publish, reason, changed = decide(
    args.tag, args.head, args.force, args.published, args.metadata_url,
  )
  print(summary(reason, changed), end="")
  # An annotation, so the run's page says it without opening the job.
  print(f"::notice title=Maven Central::{reason}")
  append("GITHUB_OUTPUT", f"publish={'true' if publish else 'false'}\n")
  append("GITHUB_STEP_SUMMARY", summary(reason, changed))
