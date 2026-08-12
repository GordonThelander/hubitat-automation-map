# Releasing to main

**Never `git merge dev` into `main`.** `dev` carries files that must not reach the public
release branch, and a merge brings all of them across in one move. `dev` has never been
merged into `main`, and that is deliberate rather than accidental.

Releases are built by copying the shipping files across explicitly.

## What ships

Only these six paths belong on `main`:

    LICENSE
    LICENSE-SUMMARY.txt
    README.md
    apps/automation_map.groovy
    check_template.sh
    packageManifest.json

## What must stay on dev

    BACKLOG.md            design notes, rejected ideas, open questions
    Supporting Docs/      research material, roughly 190KB
    repository.json       private HPM repository for beta installs

None of it is read by the app or shipped by HPM, which lists only `apps[]`. It is kept off
`main` so the public release branch stays to the point.

## Releasing

From a clean `dev`:

1. Confirm `APP_VERSION`, `packageManifest.json` `version`, and `apps[0].version` all agree.
2. Run `check_template.sh`. It catches the GString backslash trap that has silently killed
   the map page three times.
3. Check out `main`, copy the six paths above from `dev`, commit, push.
4. On `main`, `packageManifest.json` must point at the **main** branch raw URL and name the
   package `Automation Map`, not `Automation Map (Dev)`. The dev manifest differs in package
   name, app name and branch, and copying it across unchanged would repoint every existing
   user at the dev branch.

Step 4 is the one worth double-checking. It is the only file that is genuinely different
between the branches rather than simply absent from one.

## Verifying afterwards

    git ls-tree -r --name-only origin/main

Should list exactly the six paths and nothing else.
