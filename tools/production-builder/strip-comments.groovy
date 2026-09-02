// Production-builder feasibility spike, phase 1 (backlog item 16 /
// production_build_methodology.md). Strips only real Groovy comment tokens
// from a source file, using the pinned compiler's own ANTLR4 lexer -
// never regex, since // and /* */ appear inside real string/GString content
// in this codebase (URLs, embedded JS/CSS) that must survive untouched.
//
// Pin (version + jar hash) enforced by pinned-guard.groovy; comment
// classification and the ordered comparison-stream check enforced by
// comparison.groovy (factored out in phase 2 so verify-comparison-tests.groovy
// tests the exact function this script uses, not a copy that could drift -
// review, queue 434). Both resolved as a sibling of THIS running
// script's own file (not a hard-coded repo-root-relative path -
// review, queue 432) via GroovyClassLoader.parseClass() against an explicit
// File, since the plain groovy CLI does not auto-resolve a sibling .groovy
// file as an importable class the way a full project compile does.
//
// Key lexer behaviour, confirmed by probe-tokens.groovy against
// fixtures/adjacency.groovy before this was written:
//   - GroovyLexer emits both real newlines AND comments as NL-type tokens -
//     the same symbolic type. A comment's own text starts with // or /*;
//     a genuine newline's text is just the line-ending bytes. Channel is not
//     a reliable discriminator either (an inline block comment sits on
//     channel 1, but so does a standalone one in some contexts) - only the
//     token TEXT reliably identifies a comment.
//   - String/GString tokens (single, double, triple, slashy, dollar-slashy)
//     correctly hold comment-like text as part of their own token text -
//     the lexer already keeps those safe; this script never touches them.
//   - A multiline block comment is ONE NL token whose own text contains the
//     embedded line endings - those must be preserved byte-for-byte when the
//     comment is removed, or line count silently drifts (review,
//     queue 430 point 2).
//
// Usage: groovy strip-comments.groovy <input.groovy> <output.groovy>
import org.apache.groovy.parser.antlr4.GroovyLexer
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.CommonTokenStream
import groovyjarjarantlr4.v4.runtime.Token
import groovyjarjarantlr4.v4.runtime.Vocabulary
import java.security.MessageDigest
import java.nio.file.Files

File thisScriptFile
try {
    thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
} catch (Exception e) {
    System.err.println("Could not resolve this script's own source location - refusing to run: ${e.message}")
    System.exit(1)
}
File guardFile = new File(thisScriptFile.parentFile, 'pinned-guard.groovy')
File comparisonFile = new File(thisScriptFile.parentFile, 'comparison.groovy')
[guardFile, comparisonFile].each { File f ->
    if (!f.exists()) {
        System.err.println("Expected ${f.name} next to this script at ${f} - not found. " +
            'Run this script from its own checked-out location, not a copy.')
        System.exit(1)
    }
}
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()
def Comparison = new GroovyClassLoader(this.class.classLoader).parseClass(comparisonFile)

static List<Token> lexAllChannels(String src) {
    def lexer = new GroovyLexer(CharStreams.fromString(src))
    def stream = new CommonTokenStream(lexer)
    stream.fill()
    return stream.tokens as List<Token>
}

static boolean isWhitespaceChar(char c) {
    return c == (' ' as char) || c == ('\t' as char) || c == ('\r' as char) || c == ('\n' as char)
}

// Every embedded CRLF/CR/LF sequence inside a comment token's own text, in
// order, concatenated - e.g. a 4-line block comment's 3 internal line
// breaks. Empty for a single-line // or /* ... */ comment with nothing
// embedded.
static String embeddedLineEndings(String commentText, def comparisonClass) {
    StringBuilder sb = new StringBuilder()
    def m = comparisonClass.lineEndingPattern().matcher(commentText)
    while (m.find()) { sb.append(m.group()) }
    return sb.toString()
}

// Deletes exactly the comment tokens' character spans from the ORIGINAL
// source text (never reconstructs from surviving token text - this lexer
// does not tokenize whitespace at all, so concatenating token text would
// silently lose real spacing between every other token pair). Replaces each
// deleted span with whatever line-ending bytes were embedded in that
// comment's own text, preserving them exactly. Only when nothing was
// embedded (a single-line comment) does it fall back to the adjacency-space
// check - inserting one space only when neither character immediately
// outside the deleted span is already whitespace (the a/*x*/b case) -
// skipped whenever a real line ending is already being inserted, since that
// alone guarantees the deletion cannot merge two tokens.
static String stripSource(String src, List<Token> tokens, def comparisonClass) {
    StringBuilder out = new StringBuilder()
    int cursor = 0
    tokens.each { Token t ->
        if (!comparisonClass.isCommentToken(t)) return
        int start = t.startIndex
        int stop = t.stopIndex // inclusive
        if (start < cursor) return // safety: overlapping/out-of-order, skip rather than corrupt
        out.append(src, cursor, start)
        String replacement = embeddedLineEndings(t.text, comparisonClass)
        if (replacement.isEmpty()) {
            char before = start > 0 ? src.charAt(start - 1) : (' ' as char)
            char after = (stop + 1) < src.length() ? src.charAt(stop + 1) : (' ' as char)
            if (!isWhitespaceChar(before) && !isWhitespaceChar(after)) {
                replacement = ' '
            }
        }
        out.append(replacement)
        cursor = stop + 1
    }
    out.append(src, cursor, src.length())
    return out.toString()
}

// String/GString-shaped token types, resolved from the pinned lexer's own
// symbolic vocabulary rather than guessing by leading character (a leading
// '/' guess previously also matched the division operator - review,
// queue 430 point 4). Built once per run against the actual running lexer,
// not hand-copied type numbers, so this cannot go stale across a pinned
// version bump either.
static Set<Integer> stringLikeTypes() {
    def lexer = new GroovyLexer(CharStreams.fromString(''))
    Vocabulary vocab = lexer.vocabulary
    Set<Integer> types = [] as Set
    (0..vocab.maxTokenType).each { t ->
        String sym = vocab.getSymbolicName(t)
        if (sym == 'StringLiteral' || sym?.startsWith('GString')) types << t
    }
    return types
}

// Separate, narrower assertion specifically requested in review: every string/
// GString token's text is byte-identical (compared as UTF-8 bytes, not Java
// String equality alone) and in the same order.
static Map compareStringTokens(List<Token> originalTokens, List<Token> candidateTokens, Set<Integer> stringTypes, def comparisonClass) {
    List<Token> expected = originalTokens.findAll { !comparisonClass.isCommentToken(it) && stringTypes.contains(it.type) }
    List<Token> actual = candidateTokens.findAll { stringTypes.contains(it.type) }
    if (expected.size() != actual.size()) {
        return [ok: false, reason: "string-like token count differs: expected ${expected.size()}, got ${actual.size()}"]
    }
    for (int i = 0; i < expected.size(); i++) {
        if (expected[i].type != actual[i].type) {
            return [ok: false, reason: "string token ${i} type differs"]
        }
        byte[] eBytes = expected[i].text.getBytes('UTF-8')
        byte[] aBytes = actual[i].text.getBytes('UTF-8')
        if (eBytes != aBytes) {
            return [ok: false, reason: "string token ${i} UTF-8 bytes differ"]
        }
    }
    return [ok: true, stringTokenCount: actual.size()]
}

static String sha256(byte[] bytes) {
    MessageDigest md = MessageDigest.getInstance('SHA-256')
    return md.digest(bytes).collect { String.format('%02x', it) }.join('')
}

if (args.size() < 2) {
    System.err.println('Usage: groovy strip-comments.groovy <input.groovy> <output.groovy>')
    System.exit(1)
}

File inFile = new File(args[0])
File outFile = new File(args[1])
if (inFile.canonicalPath == outFile.canonicalPath) {
    System.err.println('Refusing to run: input and output resolve to the same file. This tool must never ' +
        'overwrite the annotated source it reads from - point the output at a path under tmp/.')
    System.exit(1)
}

String originalSrc = inFile.getText('UTF-8')

List<Token> originalTokens = lexAllChannels(originalSrc)
String candidateSrc = stripSource(originalSrc, originalTokens, Comparison)
List<Token> candidateTokens = lexAllChannels(candidateSrc)
Set<Integer> stringTypes = stringLikeTypes()

Map streamCheck = Comparison.compareComparisonStreams(originalTokens, candidateTokens)
Map stringCheck = compareStringTokens(originalTokens, candidateTokens, stringTypes, Comparison)

if (!streamCheck.ok) {
    System.err.println("FAIL ordered comparison-stream equivalence: ${streamCheck.reason}")
    System.exit(1)
}
if (!stringCheck.ok) {
    System.err.println("FAIL string-token equivalence: ${stringCheck.reason}")
    System.exit(1)
}

// Additional diagnostic, not the authoritative check now that the ordered
// comparison stream above proves position as well as count (review,
// queue 432) - kept because a bare count is still a useful quick signal.
int originalLineEndings = Comparison.lineEndingPattern().matcher(originalSrc).with { m -> int c = 0; while (m.find()) c++; c }
int candidateLineEndings = Comparison.lineEndingPattern().matcher(candidateSrc).with { m -> int c = 0; while (m.find()) c++; c }
if (originalLineEndings != candidateLineEndings) {
    System.err.println("FAIL line-ending count changed: original ${originalLineEndings}, candidate ${candidateLineEndings}")
    System.exit(1)
}

outFile.parentFile?.mkdirs()
outFile.setText(candidateSrc, 'UTF-8')

int commentTokenCount = originalTokens.count { Comparison.isCommentToken(it) }
long originalBytes = inFile.length()
long candidateBytes = outFile.length()
String candidateHash = sha256(Files.readAllBytes(outFile.toPath()))

println "OK - stripped ${commentTokenCount} comment token(s)."
println "Ordered comparison stream: ${streamCheck.recordCount} records, identical in kind, text and position."
println "String/GString tokens: ${stringCheck.stringTokenCount}, UTF-8 byte-identical and in order."
println "Line endings: ${originalLineEndings} in both original and candidate (diagnostic, unchanged)."
println "Original: ${originalBytes} bytes (physical file size)."
println "Candidate: ${candidateBytes} bytes (physical file size). SHA-256: ${candidateHash}"
long byteReduction = originalBytes - candidateBytes
double pct = originalBytes > 0 ? (byteReduction * 100.0 / originalBytes) : 0
println "Reduction: ${byteReduction} bytes (${String.format('%.1f', pct)}%)."
