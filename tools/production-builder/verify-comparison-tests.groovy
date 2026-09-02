// Negative-path tests for strip-comments.groovy's comparison logic (Codex
// review, queue 432): proves the ordered comparison-stream check actually
// rejects a candidate that keeps the same non-NL tokens and the same total
// newline COUNT but moves a newline to a different position relative to
// the surrounding code - the exact class of defect a bare count comparison
// (the earlier, weaker version of this check) would have missed. Concretely
// mirrors Codex's own example: `return\nx` (a bare return, then a separate
// statement `x`) versus a hand-corrupted `\nreturn x` (one statement,
// returning x) - same two real tokens, same one newline, different meaning.
//
// buildComparisonRecords()/compareComparisonStreams() below are a literal
// copy of the same-named functions in strip-comments.groovy, not a load of
// that file - dynamically loading a Script's own static methods via
// GroovyShell.parse() + .&methodName did not resolve cleanly against
// hand-built fake tokens (MissingMethodException even after relaxing the
// real script's parameter types), and this project's own convention is to
// prefer a working, inspectable duplicate over a fragile cross-file load.
// KEEP THIS IN SYNC with strip-comments.groovy if that comparison logic
// ever changes - both copies exist purely so this file can unit-test the
// algorithm in isolation, with adversarial hand-built input the real
// stripper would never actually produce itself.
//
// Usage: groovy verify-comparison-tests.groovy
import org.apache.groovy.parser.antlr4.GroovyLexer
import java.util.regex.Pattern

File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File guardFile = new File(thisScriptFile.parentFile, 'pinned-guard.groovy')
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()

static Pattern lineEndingPattern() {
    return Pattern.compile('\r\n|\r|\n')
}

static boolean isCommentText(String text) {
    return text.startsWith('//') || text.startsWith('/*')
}

static boolean isCommentToken(def t) {
    return t.type == GroovyLexer.NL && isCommentText(t.text)
}

static List buildComparisonRecords(List tokens) {
    List records = []
    tokens.each { t ->
        if (t.type == groovyjarjarantlr4.v4.runtime.Token.EOF) return
        if (t.type == GroovyLexer.NL) {
            def m = lineEndingPattern().matcher(t.text)
            while (m.find()) { records << ['NL', m.group()] }
        } else {
            records << ['TOK', t.type, t.text]
        }
    }
    return records
}

static Map compareComparisonStreams(List originalTokens, List candidateTokens) {
    List expected = buildComparisonRecords(originalTokens)
    List actual = buildComparisonRecords(candidateTokens)
    if (expected.size() != actual.size()) {
        return [ok: false, reason: "record count differs: expected ${expected.size()}, got ${actual.size()}"]
    }
    for (int i = 0; i < expected.size(); i++) {
        if (expected[i] != actual[i]) {
            return [ok: false, reason: "record ${i} differs: expected ${expected[i]}, got ${actual[i]}"]
        }
    }
    boolean candidateHasNoComments = candidateTokens.every { !isCommentToken(it) }
    if (!candidateHasNoComments) {
        return [ok: false, reason: 'candidate still contains a lexer-classified comment token']
    }
    return [ok: true, recordCount: actual.size()]
}

// Minimal fake Token: only .type/.text are read by the functions under test.
class FakeToken {
    int type
    String text
    FakeToken(int type, String text) { this.type = type; this.text = text }
}

int NL = GroovyLexer.NL
int EOF = groovyjarjarantlr4.v4.runtime.Token.EOF
// Any type distinct from NL/EOF stands in for a real code token here - the
// comparison logic under test only ever treats non-NL/non-EOF tokens
// uniformly as opaque [type, text] pairs, so the exact real Identifier
// constant is not needed to exercise it faithfully.
int IDENT = 999

int pass = 0, fail = 0
// A closure, not a method - a script-level method has its own scope and
// cannot see/mutate these local pass/fail variables, but a closure captures
// its enclosing scope by reference.
def check = { String name, boolean ok ->
    println (ok ? "PASS  ${name}" : "FAIL  ${name}")
    if (ok) { pass++ } else { fail++ }
}

// return \n x  ->  return, NL('\n'), x, EOF
List original = [
    new FakeToken(IDENT, 'return'),
    new FakeToken(NL, '\n'),
    new FakeToken(IDENT, 'x'),
    new FakeToken(EOF, '<EOF>'),
]
// \n return x  ->  NL('\n'), return, x, EOF - same tokens, same newline
// count, newline relocated to a different position relative to the code.
List corrupted = [
    new FakeToken(NL, '\n'),
    new FakeToken(IDENT, 'return'),
    new FakeToken(IDENT, 'x'),
    new FakeToken(EOF, '<EOF>'),
]

Map badResult = compareComparisonStreams(original, corrupted)
check('relocated newline (same tokens, same newline count) is rejected', !badResult.ok)
if (!badResult.ok) {
    println "      reason: ${badResult.reason}"
}

// Sanity: identical streams must still be accepted, so the check above is
// proven meaningful rather than a comparator that just always fails.
Map goodResult = compareComparisonStreams(original, original)
check('identical streams are accepted', goodResult.ok)

// LF vs CRLF at the same position, same count: also must be rejected -
// Codex's other named concern ("does not itself prove that an LF was not
// exchanged for a CRLF or bare CR").
List crlfSwapped = [
    new FakeToken(IDENT, 'return'),
    new FakeToken(NL, '\r\n'),
    new FakeToken(IDENT, 'x'),
    new FakeToken(EOF, '<EOF>'),
]
Map lineEndingKindResult = compareComparisonStreams(original, crlfSwapped)
check('LF silently exchanged for CRLF at the same position is rejected', !lineEndingKindResult.ok)

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
