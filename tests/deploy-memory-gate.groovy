// deploy-hub.ps1's memory gate (HAI #63), run against canned hub answers by
// tests/support/deploy-memory-gate.ps1. Groovy only launches it, so the suite
// runner picks it up beside the rest.
def proc = ['pwsh', '-NoProfile', '-File', 'tests/support/deploy-memory-gate.ps1'].execute()
proc.consumeProcessOutput(System.out, System.err)
proc.waitFor()
if (proc.exitValue() != 0) System.exit(1)
