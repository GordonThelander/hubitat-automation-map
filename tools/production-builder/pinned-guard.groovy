// Shared version+jar guard for every production-builder spike script -
// centralized so probe-tokens.groovy and strip-comments.groovy cannot drift
// out of sync on which Groovy build they trust (Codex review, queue 430).
//
// A plain class, not a script. The `groovy` CLI does NOT auto-resolve a
// sibling .groovy file as an importable class the way a full project
// compile does - each caller loads this file explicitly via
// `GroovyClassLoader.parseClass(File)`, resolving the File as a sibling of
// its OWN running script location (not a hard-coded repo-root-relative
// path, which silently depended on the caller's working directory - Codex
// review, queue 432).
//
// Comment classification (strip-comments.groovy) and the NL-token behaviour
// it depends on (probe-tokens.groovy) were only verified against this exact
// build. A version string match alone is not enough - a same-version jar
// could still differ in a rebuild - so this also hashes the actual jar the
// running lexer class loaded from and fails closed on any mismatch.
import org.apache.groovy.parser.antlr4.GroovyLexer
import java.security.MessageDigest

class PinnedGuard {
    static final String VERSION = '5.0.6'
    static final String JAR_SHA256 = '32338CDD9F6D842A534EA086242BF874385EE5BE6973DC3DE72F7605BF600394'

    static String sha256OfFile(File f) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        f.withInputStream { stream ->
            byte[] buf = new byte[8192]
            int n
            while ((n = stream.read(buf)) != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().collect { String.format('%02X', it) }.join('')
    }

    static void require() {
        if (GroovySystem.version != VERSION) {
            System.err.println("Pinned to Groovy ${VERSION}, running ${GroovySystem.version} - " +
                'refusing to run. Comment classification was only verified against the pinned build.')
            System.exit(1)
        }
        File jarFile
        try {
            jarFile = new File(GroovyLexer.protectionDomain.codeSource.location.toURI())
        } catch (Exception e) {
            System.err.println("Could not resolve the jar GroovyLexer loaded from - refusing to run: ${e.message}")
            System.exit(1)
        }
        String actualHash = sha256OfFile(jarFile)
        if (actualHash != JAR_SHA256) {
            System.err.println("Pinned jar SHA-256 ${JAR_SHA256}, but ${jarFile} hashes to ${actualHash} - " +
                'refusing to run. A same-version jar can still differ in a rebuild.')
            System.exit(1)
        }
    }
}
