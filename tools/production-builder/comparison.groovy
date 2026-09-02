// Shared comment-classification and ordered comparison-stream logic, used by
// BOTH strip-comments.groovy (to verify its own output before writing) and
// verify-comparison-tests.groovy (to prove the comparison logic itself would
// catch a relocated newline or a silent LF/CRLF swap). Previously duplicated
// between the two files - factored out here so they cannot drift apart
// (review, queue 434, flagged as the first thing phase 2 should do).
//
// A plain class, not a script, loaded via GroovyClassLoader.parseClass()
// against an explicit File resolved as a sibling of whichever script is
// actually running - the same pattern pinned-guard.groovy uses, for the same
// reason (the plain `groovy` CLI does not auto-resolve a sibling .groovy
// file as an importable class).
import org.apache.groovy.parser.antlr4.GroovyLexer
import java.util.regex.Pattern

class Comparison {
    static Pattern lineEndingPattern() {
        return Pattern.compile('\r\n|\r|\n')
    }

    static boolean isCommentText(String text) {
        return text.startsWith('//') || text.startsWith('/*')
    }

    // A token is a real Groovy comment only if it is the NL token type AND
    // its own text lexically begins with a comment opener - never by type
    // or channel alone. Untyped parameter so hand-built fake tokens (the
    // negative tests) can exercise this without implementing the full ANTLR
    // Token interface.
    static boolean isCommentToken(def t) {
        return t.type == GroovyLexer.NL && isCommentText(t.text)
    }

    // Builds one ordered "comparison record" list from a token stream: every
    // non-comment, non-EOF executable token becomes a [TOK, type, text]
    // record; every NL-type token - real newline OR comment - contributes
    // one [NL, lineEndingText] record per embedded CRLF/CR/LF sequence it
    // holds, with a comment's own non-newline text never emitted. Comparing
    // this stream between original and candidate proves the exact kind,
    // count, AND relative position of every line ending against every
    // surrounding executable token - not just a raw count, which would miss
    // a newline relocated relative to the code around it (Groovy's
    // return-then-newline class of risk) or an LF silently exchanged for a
    // CRLF/CR of the same count.
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
}
