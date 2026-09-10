// Reference implementation, deliberately offline.
//
// The census endpoint ships uncached (queue 557, on measured evidence: results
// are 636 to 937 bytes and complete in 76 to 206ms, against an app already
// carrying roughly 363KB of hub state). With no cache there is nothing for a
// chunk fingerprint to identify that survives the request, so computing one on
// every request would spend hub time to prove only that it can.
//
// It lives here rather than being deleted because the property it establishes is
// the one that matters if a cache is ever built: length-delimiting the ordered
// chunk names and contents means two different chunk boundaries over the same
// joined content cannot produce the same digest input by construction.
// tests/webcore-chunk-fingerprint.groovy keeps that proven.

import java.security.MessageDigest

class WebcoreChunkFingerprint {

    // SHA-256 over a length-delimited sequence of ordered chunk names and exact
    // encoded contents. MessageDigest through this same class is proven on the
    // hub by webcoreDeviceHashToken() in the app.
    static String of(Map data) {
        List settings = (data?.appSettings instanceof List) ? (data.appSettings as List) : []
        Map<Integer, String> chunks = [:]
        settings.each { Object raw ->
            if (!(raw instanceof Map)) return
            Map setting = raw as Map
            def match = ("${setting.name ?: ''}" =~ /^chunk:([0-9]+)$/)
            if (!match.matches()) return
            chunks[match[0][1] as int] = setting.value == null ? '' : "${setting.value}"
        }
        if (!chunks) return null
        StringBuilder input = new StringBuilder()
        chunks.keySet().sort().each { Integer index ->
            String name = "chunk:${index}"
            String value = chunks[index]
            input << name.length() << ':' << name << value.length() << ':' << value
        }
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] digest = md.digest(input.toString().getBytes('UTF-8'))
        StringBuilder hex = new StringBuilder()
        digest.each { byte b -> hex << String.format('%02x', b & 0xFF) }
        return hex.toString()
    }
}
