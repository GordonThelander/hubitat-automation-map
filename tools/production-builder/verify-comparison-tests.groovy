// Negative-path tests for the comparison logic in comparison.groovy
// (review, queue 432 and 434): proves the ordered comparison-stream check
// actually rejects a candidate that keeps the same non-NL tokens and the
// same total newline COUNT but moves a newline to a different position
// relative to the surrounding code - the exact class of defect a bare count
// comparison (an earlier, weaker version of this check) would have missed.
// Concretely mirrors the review's own example: `return\nx` (a bare return, then
// a separate statement `x`) versus a hand-corrupted `\nreturn x` (one
// statement, returning x) - same two real tokens, same one newline,
// different meaning.
//
// Loads the SAME comparison.groovy class strip-comments.groovy actually
// uses (factored out in phase 2 specifically so this file tests the real
// function, not a copy that could drift - queue 434) and exercises it
// against small hand-built fake tokens, adversarial input the real stripper
// would never actually produce itself.
//
// Usage: groovy verify-comparison-tests.groovy
import org.apache.groovy.parser.antlr4.GroovyLexer

File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File guardFile = new File(thisScriptFile.parentFile, 'pinned-guard.groovy')
File comparisonFile = new File(thisScriptFile.parentFile, 'comparison.groovy')
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()
def Comparison = new GroovyClassLoader(this.class.classLoader).parseClass(comparisonFile)

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

Map badResult = Comparison.compareComparisonStreams(original, corrupted)
check('relocated newline (same tokens, same newline count) is rejected', !badResult.ok)
if (!badResult.ok) {
    println "      reason: ${badResult.reason}"
}

// Sanity: identical streams must still be accepted, so the check above is
// proven meaningful rather than a comparator that just always fails.
Map goodResult = Comparison.compareComparisonStreams(original, original)
check('identical streams are accepted', goodResult.ok)

// LF vs CRLF at the same position, same count: also must be rejected -
// the review's other named concern ("does not itself prove that an LF was not
// exchanged for a CRLF or bare CR").
List crlfSwapped = [
    new FakeToken(IDENT, 'return'),
    new FakeToken(NL, '\r\n'),
    new FakeToken(IDENT, 'x'),
    new FakeToken(EOF, '<EOF>'),
]
Map lineEndingKindResult = Comparison.compareComparisonStreams(original, crlfSwapped)
check('LF silently exchanged for CRLF at the same position is rejected', !lineEndingKindResult.ok)

println "${pass} passed, ${fail} failed"
if (fail > 0) System.exit(1)
