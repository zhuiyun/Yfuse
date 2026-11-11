"""Publish only the verified, smoke-tested production APK, without updating a server."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from automatic_release_metadata import require, validate

REPOSITORY = "zhuiyun/Yfuse"
CERTIFICATE = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def gh(*args):
    return subprocess.check_output(["gh", *map(str, args)], text=True).strip()


def json_api(*args):
    return json.loads(gh("api", *args))


def prepare(apk, request_dir, output, environment=os.environ):
    require(environment["GITHUB_REPOSITORY"] == REPOSITORY and environment["GITHUB_REF"] == "refs/heads/master",
            "Automatic public packages are restricted to zhuiyun/Yfuse master")
    request = validate(request_dir, environment["GITHUB_SHA"], environment)
    require(request["mode"] == "automatic", "Manual artifact-only requests must not become public releases")
    require(apk.is_file() and not apk.is_symlink(), "Expected one regular signed APK")
    require(digest(apk) == environment["SIGNED_SHA256"] and str(apk.stat().st_size) == environment["SIGNED_SIZE"],
            "APK differs from the production-signed, smoke-tested artifact")
    require(environment["SIGNING_CERTIFICATE"] == CERTIFICATE, "Signing identity differs from the established production certificate")
    require(not output.exists() or not any(output.iterdir()), "Release output must be empty")
    output.mkdir(parents=True, exist_ok=True)
    filename = f"Yfuse-{request['versionCode']}-{request['versionName']}.apk"
    shutil.copyfile(apk, output / filename)
    for name in request["files"]:
        shutil.copyfile(request_dir / name, output / name)
    info = {"applicationId": "com.yfuse", "profile": "ycore-senplayer", "sourceBranch": "master",
            "sourceCommit": request["sourceCommit"], "versionCode": request["versionCode"],
            "versionName": request["versionName"], "apk": filename, "sha256": digest(apk),
            "size": apk.stat().st_size, "signingCertificateSha256": CERTIFICATE,
            "metadataSha256": request["files"]}
    (output / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    files = [filename, "version.properties", "release-notes.txt", "build-info.json"]
    (output / "SHA256SUMS.txt").write_text("".join(f"{digest(output / name)}  {name}\n" for name in files))
    return info, files + ["SHA256SUMS.txt"]


def release_id(tag):
    query = ("query($owner:String!, $repo:String!, $tag:String!) { repository(owner:$owner, name:$repo) "
             "{ release(tagName:$tag) { databaseId } } }")
    response = json_api("graphql", "-f", f"query={query}", "-f", "owner=zhuiyun", "-f", "repo=Yfuse", "-f", f"tag={tag}")
    release = response["data"]["repository"]["release"]
    return release["databaseId"] if release else None


def claim_tag(tag, commit):
    """Create an immutable version allocation, reconciling a concurrent create before retrying."""
    def existing():
        refs = json_api(f"repos/{REPOSITORY}/git/matching-refs/tags/{tag}")
        exact = [ref for ref in refs if ref["ref"] == "refs/tags/" + tag]
        require(len(exact) <= 1, "Duplicated version allocation tag")
        return exact[0] if exact else None

    ref = existing()
    if ref is None:
        try:
            ref = json_api("--method", "POST", f"repos/{REPOSITORY}/git/refs",
                           "-f", f"ref=refs/tags/{tag}", "-f", f"sha={commit}")
        except subprocess.CalledProcessError:
            # The request may have succeeded, or another run may own the ref now. Never
            # overwrite it or blindly retry the write when the outcome is uncertain.
            ref = existing()
    require(ref is not None and ref["object"]["type"] == "commit" and ref["object"]["sha"] == commit,
            "Version allocation is missing or already belongs to another commit")


def reject_collisions(info, releases):
    for release in releases:
        body = release.get("body") or ""
        codes = re.findall(r"(?m)^versionCode\s+(\d+)\b", body)
        names = re.findall(r"(?m)^versionCode\s+\d+\s+·\s+versionName\s+([0-9]+\.[0-9]+\.[0-9]+)\b", body)
        if re.fullmatch(r"build-[1-9][0-9]*", release["tag_name"]):
            codes.append(release["tag_name"].split("-")[1])
        if release["tag_name"].startswith("v"):
            names.append(release["tag_name"][1:])
        same_identity = str(info["versionCode"]) in codes or info["versionName"] in names
        if same_identity:
            require(release["target_commitish"] == info["sourceCommit"],
                    "A published or draft release already uses this Android version for another commit")
        if any(int(code) > info["versionCode"] for code in codes):
            previous = release["target_commitish"]
            require(re.fullmatch(r"[0-9a-f]{40}", previous),
                    "A higher published version has no immutable source commit; update the release baseline")
            comparison = json_api(f"repos/{REPOSITORY}/compare/{previous}...{info['sourceCommit']}")
            # A slower older build may finish after its descendant. It keeps its lower version
            # and never becomes Latest. A newer/divergent commit may not downgrade a known
            # manual delivery, and must establish a new explicit baseline instead.
            require(comparison["status"] == "behind",
                    "A higher delivered version precedes or diverges from this commit; update the release baseline")


def reserve_identity(info):
    # The versionCode tag is also an atomic allocation guard against rewritten history:
    # competing runs cannot publish different commits under the same Android build number.
    tag = f"build-{info['versionCode']}"
    pages = json_api("--paginate", "--slurp", f"repos/{REPOSITORY}/releases?per_page=100")
    reject_collisions(info, [release for page in pages for release in page])
    # Separate atomic claims are required: a code-only manual jump after a history rewrite
    # could otherwise reuse a versionName while getting a different code and release tag.
    claim_tag(tag, info["sourceCommit"])
    claim_tag(f"build-version-{info['versionName']}", info["sourceCommit"])
    return tag


def publish(info, files, output, request_dir):
    tag = reserve_identity(info)
    identifier = release_id(tag)
    with tempfile.TemporaryDirectory(prefix="yfuse-release-") as temporary:
        temporary = Path(temporary)
        if identifier is None:
            request = json.loads((request_dir / "release-request.json").read_text(encoding="utf-8"))
            notes = temporary / "notes.md"
            notes.write_text(
                request["releaseNotes"] + "\n\n"
                f"versionCode {info['versionCode']} · versionName {info['versionName']} · commit {info['sourceCommit']}\n\n"
                "正式签名的 YCore + SenPlayer 自动构建。已通过质量门禁、DEX/运行库验证和 Android 35/36/37 安装启动检查。\n"
                "附件含本次有效版本配置、更新说明及 SHA-256。原始 SenPlayer APK 不作为附件发布。\n"
                "此发布不更新线上更新服务器，不更改 Latest，历史版本附件不会被覆盖。\n",
                encoding="utf-8")
            gh("release", "create", tag, "--repo", REPOSITORY, "--target", info["sourceCommit"],
               "--title", f"Yfuse {info['versionName']} ({info['versionCode']})", "--draft", "--latest=false", "--notes-file", notes)
            identifier = release_id(tag)
        require(type(identifier) is int and identifier > 0, "Release could not be resolved")
        endpoint = f"repos/{REPOSITORY}/releases/{identifier}"
        release = json_api(endpoint)
        require(release["tag_name"] == tag and release["target_commitish"] == info["sourceCommit"]
                and not release["prerelease"], "Existing release names a different commit or release type")
        names = [asset["name"] for asset in release["assets"]]
        require(len(names) == len(set(names)) and set(names) <= set(files), "Unexpected or duplicated public asset")
        # Do not replace a pre-existing tag whose target differs from the release request.
        refs = json_api(f"repos/{REPOSITORY}/git/matching-refs/tags/{tag}")
        exact = [ref for ref in refs if ref["ref"] == "refs/tags/" + tag]
        require(len(exact) <= 1, "Duplicated release tag")
        if exact:
            require(exact[0]["object"]["type"] == "commit" and exact[0]["object"]["sha"] == info["sourceCommit"],
                    "Release tag points to another commit")
        else:
            require(release["draft"], "Published release is missing its tag")
        for name in files:
            if name not in names:
                require(release["draft"], "Never mutate an existing public release")
                gh("release", "upload", tag, output / name, "--repo", REPOSITORY)
        download = temporary / "download"
        download.mkdir()
        arguments = ["release", "download", tag, "--repo", REPOSITORY, "--dir", download]
        for name in files:
            arguments += ["--pattern", name]
        gh(*arguments)
        require({path.name for path in download.iterdir()} == set(files), "Downloaded release asset inventory differs")
        for name in files:
            require((download / name).read_bytes() == (output / name).read_bytes(), "Remote release bytes differ; refusing to publish or replace")
        if release["draft"]:
            gh("release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest=false")
        final = json_api(endpoint)
        require(not final["draft"] and final["target_commitish"] == info["sourceCommit"], "Release publication did not complete")
        require({asset["name"] for asset in final["assets"]} == set(files), "Published release inventory differs")
        return final["html_url"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--reserve-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.reserve_only:
            require(os.environ["GITHUB_REPOSITORY"] == REPOSITORY and os.environ["GITHUB_REF"] == "refs/heads/master",
                    "Production identities are restricted to zhuiyun/Yfuse master")
            request = validate(args.request, os.environ["GITHUB_SHA"])
            print(reserve_identity(request))
            return
        require(args.apk is not None and args.output is not None, "Publishing requires an APK and an output directory")
        info, files = prepare(args.apk, args.request, args.output)
        print(publish(info, files, args.output, args.request))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"GitHub release refused: {error}\n")


if __name__ == "__main__":
    main()
