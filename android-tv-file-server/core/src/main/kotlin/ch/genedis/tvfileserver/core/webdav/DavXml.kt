package ch.genedis.tvfileserver.core.webdav

import ch.genedis.tvfileserver.core.util.CoreLog
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/** The property names a PROPFIND asked for. */
class PropfindRequest(
    val allProp: Boolean,
    val propName: Boolean,
    /** Requested properties as `namespace to localName`. Empty when [allProp] is set. */
    val properties: List<Pair<String, String>>,
)

/** XML helpers for the WebDAV handler: safe parsing in, hand-built serialisation out. */
object DavXml {

    const val DAV_NS = "DAV:"
    private const val TAG = "DavXml"

    /**
     * A parser factory hardened against XXE.
     *
     * A WebDAV body is attacker-controlled: without these switches a `<!DOCTYPE>` with an
     * external entity would let any client on the LAN read arbitrary files off the device.
     */
    private fun newDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        factory.isXIncludeAware = false
        setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        return factory
    }

    private fun setFeatureQuietly(factory: DocumentBuilderFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (error: ParserConfigurationException) {
            // Android's parser does not know every Xerces feature name; the ones it does know
            // are enough to disable entity resolution.
            CoreLog.d(TAG, "XML parser does not support '$feature'")
        }
    }

    /** Parses an XML body, or returns null when it is empty or malformed. */
    fun parse(body: ByteArray): Document? {
        if (body.isEmpty()) return null
        return try {
            newDocumentBuilderFactory().newDocumentBuilder().parse(ByteArrayInputStream(body))
        } catch (error: Exception) {
            CoreLog.w(TAG, "Cannot parse the WebDAV request body: ${error.message}")
            null
        }
    }

    /** Interprets a PROPFIND body. An absent or unreadable body means `allprop`. */
    fun parsePropfind(body: ByteArray): PropfindRequest {
        val document = parse(body) ?: return PropfindRequest(allProp = true, propName = false, properties = emptyList())
        val root = document.documentElement ?: return PropfindRequest(true, false, emptyList())
        if (!isDav(root, "propfind")) return PropfindRequest(true, false, emptyList())

        var allProp = false
        var propName = false
        val properties = ArrayList<Pair<String, String>>(8)
        forEachElement(root) { child ->
            when {
                isDav(child, "allprop") -> allProp = true
                isDav(child, "propname") -> propName = true
                isDav(child, "prop") -> forEachElement(child) { prop ->
                    properties.add((prop.namespaceURI ?: "") to localName(prop))
                }
            }
        }
        if (!allProp && !propName && properties.isEmpty()) allProp = true
        return PropfindRequest(allProp, propName, properties)
    }

    /** Collects the properties a PROPPATCH wants to set or remove. */
    fun parseProppatch(body: ByteArray): List<Pair<String, String>> {
        val document = parse(body) ?: return emptyList()
        val root = document.documentElement ?: return emptyList()
        val properties = ArrayList<Pair<String, String>>(4)
        forEachElement(root) { action ->
            if (isDav(action, "set") || isDav(action, "remove")) {
                forEachElement(action) { propContainer ->
                    if (isDav(propContainer, "prop")) {
                        forEachElement(propContainer) { prop ->
                            properties.add((prop.namespaceURI ?: "") to localName(prop))
                        }
                    }
                }
            }
        }
        return properties
    }

    /** Reads the requested value of a single PROPPATCH property, or null. */
    fun proppatchValue(body: ByteArray, localName: String): String? {
        val document = parse(body) ?: return null
        val root = document.documentElement ?: return null
        var found: String? = null
        forEachElement(root) { action ->
            if (!isDav(action, "set")) return@forEachElement
            forEachElement(action) { propContainer ->
                if (!isDav(propContainer, "prop")) return@forEachElement
                forEachElement(propContainer) { prop ->
                    if (localName(prop).equals(localName, ignoreCase = true)) {
                        found = prop.textContent?.trim()
                    }
                }
            }
        }
        return found
    }

    /** Details of a LOCK request body. */
    class LockRequest(val exclusive: Boolean, val owner: String)

    fun parseLock(body: ByteArray): LockRequest? {
        val document = parse(body) ?: return null
        val root = document.documentElement ?: return null
        if (!isDav(root, "lockinfo")) return null
        var exclusive = true
        var owner = "unknown"
        forEachElement(root) { child ->
            when {
                isDav(child, "lockscope") -> forEachElement(child) { scope ->
                    if (isDav(scope, "shared")) exclusive = false
                }
                isDav(child, "owner") -> {
                    val text = child.textContent?.trim()
                    if (!text.isNullOrEmpty()) owner = text
                }
            }
        }
        return LockRequest(exclusive, owner)
    }

    /** Escapes text for inclusion in an XML element or attribute. */
    fun escape(text: String): String {
        val builder = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&apos;")
                else -> {
                    // XML 1.0 cannot represent most C0 controls at all; drop them rather than
                    // emit a document no client can parse.
                    if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') builder.append(ch)
                }
            }
        }
        return builder.toString()
    }

    private fun isDav(element: Element, name: String): Boolean =
        localName(element).equals(name, ignoreCase = true) &&
            (element.namespaceURI == null || element.namespaceURI == DAV_NS)

    private fun localName(element: Element): String = element.localName ?: element.nodeName.substringAfter(':')

    private inline fun forEachElement(parent: Element, action: (Element) -> Unit) {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) action(node as Element)
        }
    }
}
