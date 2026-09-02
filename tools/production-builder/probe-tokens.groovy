// Feasibility spike, phase 1: token-stream probe. Not the stripper itself -
// this just dumps what GroovyLexer actually emits for a small fixture, to
// confirm the NL-token-for-comments behaviour Codex reported before the real
// stripper is built on top of it. Guard shared with strip-comments.groovy
// via pinned-guard.groovy so the two cannot drift on which Groovy build they
// trust (Codex review, queue 430).
import org.apache.groovy.parser.antlr4.GroovyLexer
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.CommonTokenStream
import groovyjarjarantlr4.v4.runtime.Token

File thisScriptFile = new File(this.class.protectionDomain.codeSource.location.toURI())
File guardFile = new File(thisScriptFile.parentFile, 'pinned-guard.groovy')
def guard = new GroovyClassLoader(this.class.classLoader).parseClass(guardFile)
guard.require()

String src = new File(args[0]).getText('UTF-8')
def lexer = new GroovyLexer(CharStreams.fromString(src))
def tokens = new CommonTokenStream(lexer)
tokens.fill()

tokens.tokens.each { Token t ->
    String text = t.text.replace('\n', '\\n').replace('\r', '\\r')
    if (text.length() > 40) text = text[0..37] + '...'
    println "type=${t.type} ch=${t.channel} line=${t.line} col=${t.charPositionInLine} text=[${text}]"
}
