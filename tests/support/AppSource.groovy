/**
 * Real functions out of apps/automation_map.groovy, by name, for a suite to run instead of a stub.
 *
 * A stub is a second opinion about what the app does, and two of the five defects that reached the hub
 * on 2026-10-05 were hidden by stubs that disagreed with the app: hub Mode objects stubbed as plain maps,
 * and a three-argument signature invented for a function that takes different arguments in a different
 * order. A slice cannot disagree, because it is the app.
 *
 * It relies on the app's own layout rather than parsing Groovy: every top-level method starts at column 0
 * and either opens and closes on that line or ends at the next line that is a lone `}` at column 0.
 * A name defined zero times or more than once is refused, so a renamed or duplicated function fails here
 * by name instead of slicing the wrong text.
 */
class AppSource {
    static String read(File repoRoot = new File('.')) {
        File f = new File(repoRoot, 'apps/automation_map.groovy')
        assert f.isFile() : "app source not found at ${f}"
        return f.getText('UTF-8')
    }

    /** The full definition of the top-level method `name`, exactly as the app has it. */
    static String function(String source, String name) {
        List<String> lines = source.readLines()
        def head = ~/^(?:private\s+|static\s+)*[A-Za-z][\w<>\[\], ]*\s+${java.util.regex.Pattern.quote(name)}\s*\(.*/
        List<Integer> at = []
        lines.eachWithIndex { String l, int i -> if (l ==~ head) at << i }
        assert at.size() == 1 : "expected one definition of ${name} in the app, found ${at.size()}"
        int s = at[0]
        String first = lines[s]
        if (first.contains('{') && first.count('{') == first.count('}')) return first
        for (int j = s + 1; j < lines.size(); j++) {
            if (lines[j] == '}') return lines.subList(s, j + 1).join('\n')
        }
        assert false : "${name} has no closing brace at column 0"
    }

    /** The `@Field` declaration of `name`, through its closing bracket, exactly as the app has it. */
    static String field(String source, String name) {
        List<String> lines = source.readLines()
        def head = ~/^@Field\b.*\b${java.util.regex.Pattern.quote(name)}\s*=.*/
        List<Integer> at = []
        lines.eachWithIndex { String l, int i -> if (l ==~ head) at << i }
        assert at.size() == 1 : "expected one @Field ${name} in the app, found ${at.size()}"
        int depth = 0
        for (int j = at[0]; j < lines.size(); j++) {
            String code = lines[j].replaceAll(/'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"/, "''")
            depth += code.count('[') + code.count('(') + code.count('{') - code.count(']') - code.count(')') - code.count('}')
            if (depth <= 0) return lines.subList(at[0], j + 1).join('\n')
        }
        assert false : "@Field ${name} never closes"
    }

    /** Several functions, newline-separated, ready to prepend to a GroovyShell parse. */
    static String functions(String source, List<String> names) {
        return names.collect { function(source, it) }.join('\n\n')
    }
}
