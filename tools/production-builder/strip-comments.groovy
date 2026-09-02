// Production-builder feasibility spike, phase 1 (backlog item 16 /
// production_build_methodology.md). Strips only real Groovy comment tokens
// from a source file, using the pinned compiler's own ANTLR4 lexer -
// never regex, since // and /* */ appear inside real string/GString content
// in this codebase (URLs, embedded JS/CSS) that must survive untouched.
//
// Pin (version + jar hash) enforced by pinned-guard.groovy, resolved as a
// sibling of THIS running script's own file (not a hard-coded repo-root-
// relative path - Codex review, queue 432) via GroovyClassLoader.parseClass()
// against an explicit File, since the plain groovy CLI does not auto-resolve
// a sibling .groovy file as an importable class the way a full project
// compile does.
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
//     comment is removed, or line count silently drifts (Codex review,
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
import java.util.regex.Pattern

File thisScriptFile
try {
    thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
} catch (Exception e) {
    System.err.println("Could not resolve this script's own source location - refusing to run: ${e.message}")
    System.exit(1)
}
File guardFile = new File(thisScriptFile.parentFile, 'pinned-guard.groovy')
if (!guardFile.exists()) {
    System.err.println("Expected pinned-guard.groovy next to this script at ${guardFile} - not found. " +
        'Run this script from its own checked-out location, not a copy.')
    System.exit(1)
}
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()

static List<Token> lexAllChannels(String src) {
    def lexer = new GroovyLexer(CharStreams.fromString(src))
    def stream = new CommonTokenStream(lexer)
    stream.fill()
    return stream.tokens as List<Token>
}

static boolean isCommentText(String text) {
    return text.startsWith('//') || text.startsWith('/*')
}

// A token is a real Groovy comment only if it is the NL token type AND its
// own text lexically begins with a comment opener - never by type or
// channel alone (see header comment). Untyped parameter for the same
// testability reason as buildComparisonRecords() above.
static boolean isCommentToken(def t) {
    return t.type == GroovyLexer.NL && isCommentText(t.text)
}

static boolean isWhitespaceChar(char c) {
    return c == (' ' as char) || c == ('\t' as char) || c == ('\r' as char) || c == ('\n' as char)
}

static Pattern lineEndingPattern() {
    return Pattern.compile('\r\n|\r|\n')
}

// Every embedded CRLF/CR/LF sequence inside a comment token's own text, in
// order, concatenated - e.g. a 4-line block comment's 3 internal line
// breaks. Empty for a single-line // or /* ... */ comment with nothing
// embedded.
static String embeddedLineEndings(String commentText) {
    StringBuilder sb = new StringBuilder()
    def m = lineEndingPattern().matcher(commentText)
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
static String stripSource(String src, List<Token> tokens) {
    StringBuilder out = new StringBuilder()
    int cursor = 0
    tokens.each { Token t ->
        if (!isCommentToken(t)) return
        int start = t.startIndex
        int stop = t.stopIndex // inclusive
        if (start < cursor) return // safety: overlapping/out-of-order, skip rather than corrupt
        out.append(src, cursor, start)
        String replacement = embeddedLineEndings(t.text)
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

// Builds one ordered "comparison record" list from a token stream (Codex
// review, queue 432): every non-comment, non-EOF executable token becomes a
// [TOK, type, text] record; every NL-type token - real newline OR comment -
// contributes one [NL, lineEndingText] record per embedded CRLF/CR/LF
// sequence it holds, with a comment's own non-newline text never emitted.
// Comparing this stream between original and candidate proves the exact
// kind, count, AND relative position of every line ending against every
// surrounding executable token - not just a raw count, which would miss a
// newline relocated relative to the code around it (Codex's return-then-
// newline concern) or an LF silently exchanged for a CRLF/CR of the same
// count.
// Untyped List parameter deliberately, not List<Token> - so the negative-
// path tests (verify-comparison-tests.groovy) can exercise this exact
// function against small hand-built fake tokens without needing to
// implement the full ANTLR Token interface (Codex review, queue 432).
static List buildComparisonRecords(List tokens) {
    List records = []
    tokens.each { Token t ->
        if (t.type == Token.EOF) return
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

// String/GString-shaped token types, resolved from the pinned lexer's own
// symbolic vocabulary rather than guessing by leading character (a leading
// '/' guess previously also matched the division operator - Codex review,
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

// Separate, narrower assertion Codex specifically asked for: every string/
// GString token's text is byte-identical (compared as UTF-8 bytes, not Java
// String equality alone) and in the same order.
static Map compareStringTokens(List<Token> originalTokens, List<Token> candidateTokens, Set<Integer> stringTypes) {
    List<Token> expected = originalTokens.findAll { !isCommentToken(it) && stringTypes.contains(it.type) }
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
String candidateSrc = stripSource(originalSrc, originalTokens)
List<Token> candidateTokens = lexAllChannels(candidateSrc)
Set<Integer> stringTypes = stringLikeTypes()

Map streamCheck = compareComparisonStreams(originalTokens, candidateTokens)
Map stringCheck = compareStringTokens(originalTokens, candidateTokens, stringTypes)

if (!streamCheck.ok) {
    System.err.println("FAIL ordered comparison-stream equivalence: ${streamCheck.reason}")
    System.exit(1)
}
if (!stringCheck.ok) {
    System.err.println("FAIL string-token equivalence: ${stringCheck.reason}")
    System.exit(1)
}

// Additional diagnostic, not the authoritative check now that the ordered
// comparison stream above proves position as well as count (Codex review,
// queue 432) - kept because a bare count is still a useful quick signal.
int originalLineEndings = lineEndingPattern().matcher(originalSrc).with { m -> int c = 0; while (m.find()) c++; c }
int candidateLineEndings = lineEndingPattern().matcher(candidateSrc).with { m -> int c = 0; while (m.find()) c++; c }
if (originalLineEndings != candidateLineEndings) {
    System.err.println("FAIL line-ending count changed: original ${originalLineEndings}, candidate ${candidateLineEndings}")
    System.exit(1)
}

outFile.parentFile?.mkdirs()
outFile.setText(candidateSrc, 'UTF-8')

int commentTokenCount = originalTokens.count { isCommentToken(it) }
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
